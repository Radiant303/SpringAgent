package com.spring.gateway.observability;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;

/**
 * 提交前写入计时头的响应包装。
 *
 * @author hanbing
 * @since 2026-10-24
 */
final class TimingResponseWrapper extends HttpServletResponseWrapper {
    private final RequestTimings timings;
    private ServletOutputStream output;
    private PrintWriter writer;

    TimingResponseWrapper(HttpServletResponse response, RequestTimings timings) {
        super(response);
        this.timings = timings;
    }

    void prepare() {
        timings.writeHeader((HttpServletResponse) getResponse());
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        if (writer != null) {
            throw new IllegalStateException("getWriter() has already been called");
        }
        if (output == null) {
            ServletOutputStream delegate = super.getOutputStream();
            output = new ServletOutputStream() {
                @Override
                public void write(int value) throws IOException {
                    prepare();
                    delegate.write(value);
                }

                @Override
                public void write(byte[] bytes, int offset, int length) throws IOException {
                    if (length > 0) {
                        prepare();
                    }
                    delegate.write(bytes, offset, length);
                }

                @Override
                public void flush() throws IOException {
                    prepare();
                    delegate.flush();
                }

                @Override
                public void close() throws IOException {
                    prepare();
                    delegate.close();
                }

                @Override
                public boolean isReady() {
                    return delegate.isReady();
                }

                @Override
                public void setWriteListener(WriteListener listener) {
                    delegate.setWriteListener(listener);
                }
            };
        }
        return output;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        if (output != null) {
            throw new IllegalStateException("getOutputStream() has already been called");
        }
        if (writer == null) {
            PrintWriter delegate = super.getWriter();
            writer = new PrintWriter(new Writer() {
                @Override
                public void write(char[] chars, int offset, int length) {
                    if (length > 0) {
                        prepare();
                    }
                    delegate.write(chars, offset, length);
                }

                @Override
                public void flush() {
                    prepare();
                    delegate.flush();
                }

                @Override
                public void close() {
                    prepare();
                    delegate.close();
                }
            });
        }
        return writer;
    }

    @Override
    public void flushBuffer() throws IOException {
        prepare();
        if (writer != null) {
            writer.flush();
        }
        super.flushBuffer();
    }

    @Override
    public void sendError(int status) throws IOException {
        prepare();
        super.sendError(status);
    }

    @Override
    public void sendError(int status, String message) throws IOException {
        prepare();
        super.sendError(status, message);
    }

    @Override
    public void sendRedirect(String location) throws IOException {
        prepare();
        super.sendRedirect(location);
    }

    @Override
    public void sendRedirect(String location, boolean clearBuffer) throws IOException {
        prepare();
        super.sendRedirect(location, clearBuffer);
    }

    @Override
    public void sendRedirect(String location, int status) throws IOException {
        prepare();
        super.sendRedirect(location, status);
    }

    @Override
    public void sendRedirect(String location, int status, boolean clearBuffer) throws IOException {
        prepare();
        super.sendRedirect(location, status, clearBuffer);
    }

    @Override
    public void reset() {
        super.reset();
        output = null;
        writer = null;
    }
}
