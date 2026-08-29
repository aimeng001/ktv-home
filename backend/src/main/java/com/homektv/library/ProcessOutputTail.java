package com.homektv.library;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Objects;

/** Drains a child-process stream while retaining only its diagnostic tail. */
final class ProcessOutputTail {

    private ProcessOutputTail() { }

    static String read(InputStream input, Charset charset, int maxBytes) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(charset, "charset");
        if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes must be positive");

        byte[] tail = new byte[maxBytes];
        byte[] buffer = new byte[Math.min(8192, maxBytes)];
        int position = 0;
        int size = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            for (int index = 0; index < read; index++) {
                tail[position] = buffer[index];
                position = (position + 1) % maxBytes;
                if (size < maxBytes) size++;
            }
        }

        byte[] result = new byte[size];
        int start = size == maxBytes ? position : 0;
        for (int index = 0; index < size; index++) {
            result[index] = tail[(start + index) % maxBytes];
        }
        return new String(result, charset);
    }
}
