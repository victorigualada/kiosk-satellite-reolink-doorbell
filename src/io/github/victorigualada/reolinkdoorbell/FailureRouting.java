// SPDX-License-Identifier: Apache-2.0
package io.github.victorigualada.reolinkdoorbell;

/** Setup fixes connection/configuration failures, not Android decoder failures. */
final class FailureRouting {
    static boolean setup(Exception error, boolean connecting) {
        return connecting || error instanceof java.net.SocketException || error instanceof java.net.SocketTimeoutException
            || error instanceof java.net.UnknownHostException || error instanceof javax.net.ssl.SSLException || error instanceof java.io.EOFException;
    }
    /** Nothing listens at the address, or nothing is there at all: what an address change looks like. */
    static boolean unreachable(Exception error) {
        return error instanceof java.net.ConnectException || error instanceof java.net.NoRouteToHostException
            || error instanceof java.net.SocketTimeoutException;
    }
}
