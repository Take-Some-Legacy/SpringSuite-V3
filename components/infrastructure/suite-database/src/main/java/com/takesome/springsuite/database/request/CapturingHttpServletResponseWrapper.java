package com.takesome.springsuite.database.request;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

final class CapturingHttpServletResponseWrapper extends HttpServletResponseWrapper {
    private final int captureLimit;
    private final ByteArrayOutputStream capture;
    private long totalBytes;
    private ServletOutputStream outputStream;
    private PrintWriter writer;

    CapturingHttpServletResponseWrapper(HttpServletResponse response, int captureLimit) {
        super(response);
        this.captureLimit = Math.max(0, captureLimit);
        this.capture = new ByteArrayOutputStream(Math.min(this.captureLimit, 16_384));
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        if (writer != null) throw new IllegalStateException("getWriter() has already been called");
        if (outputStream == null) {
            ServletOutputStream delegate = getResponse().getOutputStream();
            outputStream = new ServletOutputStream() {
                @Override public boolean isReady() { return delegate.isReady(); }
                @Override public void setWriteListener(WriteListener listener) { delegate.setWriteListener(listener); }
                @Override public void write(int value) throws IOException { delegate.write(value); capture(value); }
                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    delegate.write(bytes, offset, length);
                    capture(bytes, offset, length);
                }
                @Override public void flush() throws IOException { delegate.flush(); }
                @Override public void close() throws IOException { delegate.close(); }
            };
        }
        return outputStream;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        if (outputStream != null) throw new IllegalStateException("getOutputStream() has already been called");
        if (writer == null) {
            Charset charset = resolveCharset(getCharacterEncoding());
            writer = new PrintWriter(new OutputStreamWriter(getOutputStream(), charset), false);
        }
        return writer;
    }

    byte[] capturedBody() { flushCapture(); return capture.toByteArray(); }
    long totalBytes() { flushCapture(); return totalBytes; }
    boolean truncated() { return totalBytes > capture.size(); }

    private void capture(int value) {
        totalBytes++;
        if (capture.size() < captureLimit) capture.write(value);
    }

    private void capture(byte[] bytes, int offset, int length) {
        totalBytes += length;
        int remaining = captureLimit - capture.size();
        if (remaining > 0) capture.write(bytes, offset, Math.min(remaining, length));
    }

    private void flushCapture() { if (writer != null) writer.flush(); }

    private static Charset resolveCharset(String charsetName) {
        try {
            return charsetName == null || charsetName.isBlank() ? StandardCharsets.UTF_8 : Charset.forName(charsetName);
        } catch (RuntimeException ex) {
            return StandardCharsets.UTF_8;
        }
    }
}
