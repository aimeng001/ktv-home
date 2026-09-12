package com.homektv.ws;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Serializes JSON into a hard byte budget without materializing an oversized array. */
final class BoundedJson {
    private BoundedJson() { }

    static byte[] serialize(ObjectMapper mapper, Object value, int maxBytes) {
        CappedOutputStream output = new CappedOutputStream(maxBytes);
        try {
            mapper.writeValue(output, value);
            return output.toByteArray();
        } catch (IOException exception) {
            if (hasCause(exception, LimitExceeded.class)) return null;
            throw new IllegalStateException("JSON serialization failed", exception);
        }
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (type.isInstance(current)) return true;
        }
        return false;
    }

    private static final class LimitExceeded extends IOException {
        private LimitExceeded() {
            super("JSON payload exceeds the byte budget");
        }
    }

    private static final class CappedOutputStream extends OutputStream {
        private final int maxBytes;
        private final ByteArrayOutputStream delegate;
        private int written;

        private CappedOutputStream(int maxBytes) {
            if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes must be positive");
            this.maxBytes = maxBytes;
            this.delegate = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        }

        @Override
        public void write(int value) throws IOException {
            ensureCapacity(1);
            delegate.write(value);
            written++;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            if (bytes == null) throw new NullPointerException("bytes");
            if (offset < 0 || length < 0 || offset > bytes.length - length) {
                throw new IndexOutOfBoundsException();
            }
            ensureCapacity(length);
            delegate.write(bytes, offset, length);
            written += length;
        }

        private void ensureCapacity(int additional) throws IOException {
            if (additional > maxBytes - written) throw new LimitExceeded();
        }

        private byte[] toByteArray() {
            return delegate.toByteArray();
        }
    }
}
