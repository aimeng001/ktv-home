package com.homektv.ai;

import com.homektv.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

class AiAutoApplyPolicyTest {
    @Test
    void autoAppliesAtConfiguredNinetyPercentThreshold() {
        AppProperties properties = new AppProperties();
        properties.getAi().setAutoApplyConfidence(0.90);
        AiAutoApplyPolicy policy = new AiAutoApplyPolicy(properties);

        assertThat(policy.shouldAutoApply(result(0.90))).isTrue();
        assertThat(policy.shouldAutoApply(result(0.95))).isTrue();
        assertThat(policy.shouldAutoApply(result(0.899))).isFalse();
    }

    @Test
    void doesNotAutoApplyWhenClassificationIsNull() {
        AppProperties properties = new AppProperties();
        properties.getAi().setAutoApplyConfidence(0.90);

        assertThat(new AiAutoApplyPolicy(properties).shouldAutoApply(null)).isFalse();
    }

    @Test
    void usesCurrentResolvedThresholdWhenConfigServiceIsAvailable() {
        AiConfigService configService = mock(AiConfigService.class);
        when(configService.resolve()).thenReturn(new AiConfigService.ResolvedConfig(
                true, "http://localhost:8080/v1", "bulk", null, 5, 0.9, 0.8,
                AiConfigService.JsonMode.AUTO, 2, 1, null));

        AiAutoApplyPolicy policy = new AiAutoApplyPolicy(configService);

        assertThat(policy.threshold()).isEqualTo(0.8);
        assertThat(policy.shouldAutoApply(result(0.8))).isTrue();
        assertThat(policy.shouldAutoApply(result(0.79))).isFalse();
    }

    @Test
    void classificationClampsInvalidConfidenceValues() {
        assertThat(result(1.4).confidence()).isEqualTo(1.0);
        assertThat(result(-0.2).confidence()).isZero();
    }

    private AiSongClassification result(double confidence) {
        return new AiSongClassification("国语", "00年代", List.of("流行"), List.of("爱情"),
                "全年龄", "组合", List.of("华语流行"), "测试", confidence);
    }
}
