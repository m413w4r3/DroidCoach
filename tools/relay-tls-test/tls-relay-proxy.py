#!/usr/bin/env python3
"""Loopback-only TLS terminator forwarding raw WebSocket bytes to Paseo Relay."""

from __future__ import annotations

import argparse
import select
import signal
import socket
import ssl
import threading

UPSTREAM = ("127.0.0.1", 4400)
LISTEN_HOST = "127.0.0.1"
active_clients: set[ssl.SSLSocket] = set()
active_lock = threading.Lock()


def interrupt_sessions(_signum: int, _frame: object) -> None:
    with active_lock:
        clients = tuple(active_clients)
    for client in clients:
        try:
            client.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass


def forward_tls(client_socket: socket.socket, context: ssl.SSLContext) -> None:
    tls_client: ssl.SSLSocket | None = None
    upstream: socket.socket | None = None
    try:
        client_socket.settimeout(5)
        tls_client = context.wrap_socket(client_socket, server_side=True)
        with active_lock:
            active_clients.add(tls_client)
        upstream = socket.create_connection(UPSTREAM, timeout=5)
        tls_client.settimeout(None)
        upstream.settimeout(None)

        while True:
            readable, _, _ = select.select((tls_client, upstream), (), ())
            for source in readable:
                payload = source.recv(65_536)
                if not payload:
                    return
                destination = upstream if source is tls_client else tls_client
                destination.sendall(payload)
    except (OSError, ssl.SSLError):
        # Handshake failures never open the fixed upstream connection.
        return
    finally:
        if tls_client is not None:
            with active_lock:
                active_clients.discard(tls_client)
            try:
                tls_client.close()
            except OSError:
                pass
        else:
            try:
                client_socket.close()
            except OSError:
                pass
        if upstream is not None:
            try:
                upstream.close()
            except OSError:
                pass


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=4443)
    parser.add_argument("--cert", required=True)
    parser.add_argument("--key", required=True)
    args = parser.parse_args()
    if not 0 <= args.port <= 65_535:
        parser.error("--port must be between 0 and 65535")

    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(certfile=args.cert, keyfile=args.key)
    signal.signal(signal.SIGUSR1, interrupt_sessions)

    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.bind((LISTEN_HOST, args.port))
    listener.listen(32)
    print(f"READY {LISTEN_HOST}:{listener.getsockname()[1]}", flush=True)
    try:
        while True:
            client, _ = listener.accept()
            threading.Thread(target=forward_tls, args=(client, context), daemon=True).start()
    except KeyboardInterrupt:
        pass
    finally:
        listener.close()


if __name__ == "__main__":
    main()
