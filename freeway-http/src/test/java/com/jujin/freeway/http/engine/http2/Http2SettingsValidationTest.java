package com.jujin.freeway.http.engine.http2;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;

import com.jujin.freeway.http.engine.http2.frame.FrameFlag;
import com.jujin.freeway.http.engine.http2.frame.FrameHeader;
import com.jujin.freeway.http.engine.http2.frame.FrameType;
import com.jujin.freeway.http.engine.http2.frame.SettingIdentifier;
import com.jujin.freeway.http.engine.http2.frame.SettingParameter;
import com.jujin.freeway.http.engine.http2.frame.SettingsFrame;
import com.jujin.freeway.http.engine.http2.util.Http2ErrorCode;
import com.jujin.freeway.http.engine.http2.util.Http2Exception;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SETTINGS_HEADER_TABLE_SIZE validation at the connection boundary. The wire
 * parse is unsigned, so an out-of-range value can only reach the connection
 * through a programmatically built SettingsFrame (e.g. the h2c upgrade
 * path) — it must fail as a connection error instead of poisoning the HPACK
 * decoder state with a negative dynamic-table cap.
 */
class Http2SettingsValidationTest {

    @Test
    void negativeHeaderTableSizeIsProtocolError() throws Exception {
        try (SocketPair pair = SocketPair.open();
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Http2Connection connection = new Http2Connection(pair.client,
                pair.client.getInputStream(), pair.client.getOutputStream(),
                executor, (s, i, o, h) -> {}, 0);
            var settings = new SettingsFrame(new FrameHeader(0,
                FrameType.SETTINGS, FrameFlag.NONE, 0));
            settings.params.add(new SettingParameter(
                SettingIdentifier.SETTINGS_HEADER_TABLE_SIZE, -1));
            var ex = assertDoesNotThrow(() -> {
                try {
                    connection.applyUpgradeSettings(settings);
                    return (Http2Exception) null;
                } catch (Http2Exception e) {
                    return e;
                }
            });
            assertEquals(Http2ErrorCode.PROTOCOL_ERROR, ex.errorCode());
        }
    }

    @Test
    void outOfUint32HeaderTableSizeIsProtocolError() throws Exception {
        try (SocketPair pair = SocketPair.open();
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Http2Connection connection = new Http2Connection(pair.client,
                pair.client.getInputStream(), pair.client.getOutputStream(),
                executor, (s, i, o, h) -> {}, 0);
            var settings = new SettingsFrame(new FrameHeader(0,
                FrameType.SETTINGS, FrameFlag.NONE, 0));
            settings.params.add(new SettingParameter(
                SettingIdentifier.SETTINGS_HEADER_TABLE_SIZE, 0x1_0000_0000L));
            var ex = assertDoesNotThrow(() -> {
                try {
                    connection.applyUpgradeSettings(settings);
                    return (Http2Exception) null;
                } catch (Http2Exception e) {
                    return e;
                }
            });
            assertEquals(Http2ErrorCode.PROTOCOL_ERROR, ex.errorCode());
        }
    }

    @Test
    void validHeaderTableSizesAreAccepted() throws Exception {
        try (SocketPair pair = SocketPair.open();
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Http2Connection connection = new Http2Connection(pair.client,
                pair.client.getInputStream(), pair.client.getOutputStream(),
                executor, (s, i, o, h) -> {}, 0);
            for (long value : new long[] {0, 4096, 0xFFFFFFFFL}) {
                var settings = new SettingsFrame(new FrameHeader(0,
                    FrameType.SETTINGS, FrameFlag.NONE, 0));
                settings.params.add(new SettingParameter(
                    SettingIdentifier.SETTINGS_HEADER_TABLE_SIZE, value));
                assertDoesNotThrow(
                    () -> connection.applyUpgradeSettings(settings),
                    "valid SETTINGS_HEADER_TABLE_SIZE=" + value
                        + " must be accepted");
            }
        }
    }

    private static final class SocketPair implements AutoCloseable {
        private final ServerSocket server;
        private final Socket client;

        private SocketPair(ServerSocket server, Socket client) {
            this.server = server;
            this.client = client;
        }

        static SocketPair open() throws IOException {
            ServerSocket server = new ServerSocket(0);
            Socket client = new Socket("127.0.0.1", server.getLocalPort());
            return new SocketPair(server, client);
        }

        @Override
        public void close() throws IOException {
            client.close();
            server.close();
        }
    }
}
