package org.mockserver.socket;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Random;

/**
 * @author jamesdbloom
 */
public class PortFactory {

    private static final Random random = new Random();

    public static int findFreePort() {
        int[] freePorts = findAvailablePorts(1);
        return freePorts[random.nextInt(freePorts.length)];
    }

    /**
     * Find multiple free ports. Ports are selected from a larger pool of recently-available
     * ports to reduce the chance of collisions. Callers should handle {@code BindException}
     * as the returned ports may be claimed by another process before the caller binds them.
     *
     * @param count the number of free ports to find (must be between 1 and 1000 inclusive)
     * @return an array of {@code count} distinct port numbers that were recently free
     * @throws IllegalArgumentException if count is not between 1 and 1000
     */
    public static int[] findFreePorts(int count) {
        if (count <= 0 || count > 1000) {
            throw new IllegalArgumentException("count must be between 1 and 1000, was: " + count);
        }
        int[] candidates = findAvailablePorts(count);
        int ratio = candidates.length / count;
        int[] result = new int[count];
        for (int i = 0; i < count; i++) {
            result[i] = candidates[i * ratio];
        }
        return result;
    }

    private static int[] findAvailablePorts(int number) {
        // Hold all sockets open simultaneously before closing any, so the OS cannot recycle a port
        // number to a second socket in the same batch (they are bound sequentially but all kept open
        // until every port is recorded), then release them in a finally block. SO_REUSEADDR is set so
        // a caller can re-bind a just-released port without waiting for lingering TIME_WAIT state.
        // There is deliberately no sleep after closing: a delay between releasing the ports and
        // returning them only widens the window in which another process can claim a port, so callers
        // must still handle BindException - binding the real socket directly is the only fully
        // race-free option.
        int arraySize = number + random.nextInt(60);
        int[] port = new int[arraySize];
        ServerSocket[] serverSockets = new ServerSocket[arraySize];
        try {
            for (int i = arraySize - 1; i >= 0; i--) {
                ServerSocket serverSocket = new ServerSocket();
                // store immediately so the finally block closes it even if setReuseAddress/bind throws
                serverSockets[i] = serverSocket;
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(0));
                port[i] = serverSocket.getLocalPort();
            }
            return port;
        } catch (IOException e) {
            throw new RuntimeException("Exception while trying to find a free port", e);
        } finally {
            for (ServerSocket serverSocket : serverSockets) {
                if (serverSocket != null) {
                    try {
                        serverSocket.close();
                    } catch (IOException ignore) {
                        // best effort - the port has already been recorded
                    }
                }
            }
        }
    }
}
