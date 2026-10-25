package com.homektv.ai;

import com.homektv.web.ApiException;
import org.junit.jupiter.api.Test;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiBaseUrlPolicyTest {
    @Test
    void allowsPublicHttpsEndpoint() {
        assertThatCode(() -> AiBaseUrlPolicy.requireSafe("https://8.8.8.8/v1", false))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsPublicHttpEndpoint() {
        assertThatThrownBy(() -> AiBaseUrlPolicy.requireSafe("http://8.8.8.8/v1", false))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getCode())
                .isEqualTo("INVALID_AI_CONFIG");
    }

    @Test
    void rejectsPrivateEndpointUnlessExplicitlyAllowed() {
        assertThatThrownBy(() -> AiBaseUrlPolicy.requireSafe("http://127.0.0.1:11434/v1", false))
                .isInstanceOf(ApiException.class);

        assertThatCode(() -> AiBaseUrlPolicy.requireSafe("http://127.0.0.1:11434/v1", true))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsIpv4MappedLoopbackAddress() {
        assertThatThrownBy(() -> AiBaseUrlPolicy.requireSafe("http://[::ffff:127.0.0.1]:11434/v1", false))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsCredentialsQueryAndFragment() {
        assertThatThrownBy(() -> AiBaseUrlPolicy.requireSafe("https://user:secret@8.8.8.8/v1", false))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> AiBaseUrlPolicy.requireSafe("https://8.8.8.8/v1?key=secret", false))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> AiBaseUrlPolicy.requireSafe("https://8.8.8.8/v1#fragment", false))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsUnsupportedSchemesAndUrlsWithoutAnAuthority() {
        for (String url : new String[]{"ftp://8.8.8.8/v1", "http:///v1", "https://"}) {
            assertThatThrownBy(() -> AiBaseUrlPolicy.normalize(url))
                    .as("%s must not be accepted as an API base URL", url)
                    .isInstanceOf(ApiException.class)
                    .extracting(exception -> ((ApiException) exception).getCode())
                    .isEqualTo("INVALID_AI_CONFIG");
        }
    }

    @Test
    void normalizesTrailingSlashWithoutExposingSecrets() {
        assertThat(AiBaseUrlPolicy.normalizeAndValidate(" https://8.8.8.8/v1/// ", false))
                .isEqualTo("https://8.8.8.8/v1");
    }

    @Test
    void classifiesReservedAndPrivateIpv4RangesAtTheirBoundaries() {
        for (String host : new String[]{
                "0.0.0.0", "0.1.2.3", "10.0.0.1", "127.0.0.2", "224.0.0.1",
                "100.64.0.1", "100.127.255.254", "169.254.1.1",
                "172.16.0.1", "172.31.255.254", "192.168.1.1",
                "192.0.0.1", "198.18.0.1", "198.19.255.254"
        }) {
            assertThat(AiBaseUrlPolicy.isPrivateNetwork("https://" + host))
                    .as("%s must be classified as private/local", host)
                    .isTrue();
        }

        for (String host : new String[]{
                "8.8.8.8", "100.63.255.254", "100.128.0.1",
                "172.15.255.254", "172.32.0.1", "192.0.1.1",
                "198.17.255.254", "198.20.0.1"
        }) {
            assertThat(AiBaseUrlPolicy.isPrivateNetwork("https://" + host))
                    .as("%s must remain public", host)
                    .isFalse();
        }
    }

    @Test
    void classifiesIpv6UniqueLocalAndMappedAddressesWithoutTreatingGlobalIpv6AsPrivate() {
        assertThat(AiBaseUrlPolicy.isPrivateNetwork("https://[fc00::1]"))
                .as("IPv6 unique-local lower boundary")
                .isTrue();
        assertThat(AiBaseUrlPolicy.isPrivateNetwork("https://[fdff:ffff::1]"))
                .as("IPv6 unique-local upper boundary")
                .isTrue();
        assertThat(AiBaseUrlPolicy.isPrivateNetwork("https://[2001:4860:4860::8888]"))
                .as("global IPv6")
                .isFalse();
        assertThat(AiBaseUrlPolicy.isPrivateNetwork("https://[::8.8.8.8]"))
                .as("IPv4-compatible IPv6")
                .isFalse();

        assertThat(AiBaseUrlPolicy.isPrivateNetwork("https://[::ffff:8.8.8.8]"))
                .as("IPv4-mapped public IPv6")
                .isFalse();
    }

    @Test
    void classifiesExplicitIpv4MappedIpv6ByItsEmbeddedAddress() throws Exception {
        byte[] privateMappedBytes = new byte[16];
        privateMappedBytes[10] = (byte) 0xff;
        privateMappedBytes[11] = (byte) 0xff;
        privateMappedBytes[12] = 10;
        privateMappedBytes[15] = 1;
        InetAddress privateMapped = Inet6Address.getByAddress(null, privateMappedBytes, -1);

        byte[] publicMappedBytes = privateMappedBytes.clone();
        publicMappedBytes[12] = 8;
        publicMappedBytes[13] = 8;
        publicMappedBytes[14] = 8;
        publicMappedBytes[15] = 8;
        InetAddress publicMapped = Inet6Address.getByAddress(null, publicMappedBytes, -1);

        assertThat(AiBaseUrlPolicy.isPrivateNetwork("https://mapped.test/v1",
                host -> new InetAddress[]{privateMapped})).isTrue();
        assertThat(AiBaseUrlPolicy.isPrivateNetwork("https://mapped.test/v1",
                host -> new InetAddress[]{publicMapped})).isFalse();
    }

    @Test
    void blankBaseUrlRemainsDisabledWithoutAddressResolution() {
        assertThat(AiBaseUrlPolicy.normalize(null)).isEmpty();
        assertThat(AiBaseUrlPolicy.normalizeAndValidate("  ", false)).isEmpty();
        assertThat(AiBaseUrlPolicy.isPrivateNetwork("  ")).isFalse();
        assertThatCode(() -> AiBaseUrlPolicy.requireSafe(null, false)).doesNotThrowAnyException();
    }

    @Test
    void rejectsUnresolvableDestinationBeforeSendingCredentialsWithoutUsingSystemDns() {
        AiBaseUrlPolicy.AddressResolver unresolved = host -> { throw new UnknownHostException(host); };

        assertThatThrownBy(() -> AiBaseUrlPolicy.requireSafe(
                "https://ktv-home-review.invalid/v1", false, unresolved))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getCode())
                .isEqualTo("INVALID_AI_CONFIG");
    }

    @Test
    void rejectsPlainHttpWhenDnsResolvesToBothPrivateAndPublicAddresses() throws Exception {
        InetAddress[] mixedAddresses = {
                InetAddress.getByAddress(new byte[]{10, 0, 0, 1}),
                InetAddress.getByAddress(new byte[]{8, 8, 8, 8})
        };
        AiBaseUrlPolicy.AddressResolver resolver = host -> {
            assertThat(host).isEqualTo("multi-address.test");
            return mixedAddresses;
        };

        assertThat(AiBaseUrlPolicy.isPrivateNetwork("http://multi-address.test/v1", resolver)).isFalse();
        assertThatThrownBy(() -> AiBaseUrlPolicy.requireSafe("http://multi-address.test/v1", true, resolver))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getCode())
                .isEqualTo("INVALID_AI_CONFIG");
    }

    @Test
    void requiresOptInForPrivateHttpsButKeepsPublicHttpsAllowedAfterOptIn() throws Exception {
        InetAddress privateAddress = InetAddress.getByAddress(new byte[]{10, 20, 30, 40});
        InetAddress publicAddress = InetAddress.getByAddress(new byte[]{8, 8, 8, 8});
        AiBaseUrlPolicy.AddressResolver privateResolver = host -> new InetAddress[]{privateAddress};
        AiBaseUrlPolicy.AddressResolver mixedResolver = host -> new InetAddress[]{privateAddress, publicAddress};

        assertThatThrownBy(() -> AiBaseUrlPolicy.requireSafe("https://private.test/v1", false, privateResolver))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("禁止访问本机或内网地址");
        assertThatCode(() -> AiBaseUrlPolicy.requireSafe("https://private.test/v1", true, privateResolver))
                .doesNotThrowAnyException();
        assertThatCode(() -> AiBaseUrlPolicy.requireSafe("https://mixed.test/v1", true, mixedResolver))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> AiBaseUrlPolicy.requireSafe("https://mixed.test/v1", false, mixedResolver))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("禁止访问本机或内网地址");
    }

    @Test
    void rejectsMalformedUrlsAndReturnsFalseForUnexpectedResolverErrors() {
        AiBaseUrlPolicy.AddressResolver unexpectedFailure = host -> {
            throw new IllegalStateException("synthetic resolver failure");
        };

        assertThatThrownBy(() -> AiBaseUrlPolicy.isPrivateNetwork("not a URL", unexpectedFailure))
                .isInstanceOf(ApiException.class);
        assertThat(AiBaseUrlPolicy.isPrivateNetwork("https://resolver.test/v1", unexpectedFailure)).isFalse();
    }

    @Test
    void treatsEmptyAndFailedAddressResolutionAsNotPrivate() {
        AiBaseUrlPolicy.AddressResolver empty = host -> new InetAddress[0];
        AiBaseUrlPolicy.AddressResolver failed = host -> { throw new UnknownHostException(host); };

        assertThat(AiBaseUrlPolicy.isPrivateNetwork("https://resolver.test/v1", empty)).isFalse();
        assertThat(AiBaseUrlPolicy.isPrivateNetwork("https://resolver.test/v1", failed)).isFalse();
    }

    @Test
    void requiresAnOptedInPrivateAddressForPlainHttp() throws Exception {
        InetAddress publicAddress = InetAddress.getByAddress(new byte[]{8, 8, 8, 8});
        AiBaseUrlPolicy.AddressResolver publicResolver = host -> new InetAddress[]{publicAddress};
        AiBaseUrlPolicy.AddressResolver emptyResolver = host -> new InetAddress[0];

        assertThatThrownBy(() -> AiBaseUrlPolicy.requireSafe("http://resolver.test/v1", true, publicResolver))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("必须使用 HTTPS");
        assertThatThrownBy(() -> AiBaseUrlPolicy.requireSafe("http://resolver.test/v1", true, emptyResolver))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("必须使用 HTTPS");
    }

    @Test
    void recognizesIpv6LocalLinkLocalSiteLocalAndMulticastAddresses() throws Exception {
        for (byte[] bytes : new byte[][]{
                new byte[16],
                new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1},
                new byte[]{(byte) 0xfe, (byte) 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1},
                new byte[]{(byte) 0xfe, (byte) 0xc0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1},
                new byte[]{(byte) 0xff, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1}
        }) {
            InetAddress address = InetAddress.getByAddress(bytes);
            assertThat(AiBaseUrlPolicy.isPrivateNetwork("https://resolver.test/v1", host -> new InetAddress[]{address}))
                    .as("%s must be classified as local", address.getHostAddress())
                    .isTrue();
        }
    }
}
