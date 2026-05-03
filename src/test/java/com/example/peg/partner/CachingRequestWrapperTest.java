package com.example.peg.partner;

import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CachingRequestWrapperTest {

    @Test
    void body_isReadableMultipleTimes() throws Exception {
        HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        byte[] payload = "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8);
        CachingRequestWrapper wrapper = new CachingRequestWrapper(req, payload);

        assertThat(wrapper.body()).containsExactly(payload);

        ServletInputStream first = wrapper.getInputStream();
        ServletInputStream second = wrapper.getInputStream();

        assertThat(first.readAllBytes()).containsExactly(payload);
        // Second read still returns the full body — wrapper buffers internally
        assertThat(second.readAllBytes()).containsExactly(payload);
    }

    @Test
    void getInputStream_streamLifecycle() throws Exception {
        HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        byte[] payload = "ab".getBytes(StandardCharsets.UTF_8);
        CachingRequestWrapper wrapper = new CachingRequestWrapper(req, payload);

        ServletInputStream in = wrapper.getInputStream();
        assertThat(in.isReady()).isTrue();
        assertThat(in.isFinished()).isFalse();

        assertThat(in.read()).isEqualTo((int) 'a');
        assertThat(in.read()).isEqualTo((int) 'b');
        assertThat(in.read()).isEqualTo(-1);
        assertThat(in.isFinished()).isTrue();

        // setReadListener is a no-op, call it for coverage and ensure it doesn't throw
        in.setReadListener(null);
    }

    @Test
    void getReader_returnsBufferedReaderOverBody() throws Exception {
        HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        CachingRequestWrapper wrapper = new CachingRequestWrapper(req,
                "line one".getBytes(StandardCharsets.UTF_8));

        try (BufferedReader reader = wrapper.getReader()) {
            assertThat(reader.readLine()).isEqualTo("line one");
        }
    }
}
