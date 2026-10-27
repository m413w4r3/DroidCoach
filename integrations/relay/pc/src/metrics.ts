import { createServer, type Server } from "node:http";

export class ConnectorMetrics {
  connectionsOpened = 0;
  sessionsOpened = 0;
  requests = 0;
  errors = 0;
  uncertainOutcomes = 0;
  latencyTotalMs = 0;
  latencyMaxMs = 0;
  relayConnected = false;
  private activeSessions = 0;
  private metricsServer: Server | null = null;

  connectionOpened(): void {
    this.connectionsOpened += 1;
  }

  sessionOpened(): void {
    this.sessionsOpened += 1;
    this.activeSessions += 1;
  }

  sessionClosed(): void {
    this.activeSessions = Math.max(0, this.activeSessions - 1);
  }

  request(): void {
    this.requests += 1;
  }

  error(): void {
    this.errors += 1;
  }

  uncertainOutcome(): void {
    this.uncertainOutcomes += 1;
  }

  latency(milliseconds: number): void {
    this.latencyTotalMs += milliseconds;
    this.latencyMaxMs = Math.max(this.latencyMaxMs, milliseconds);
  }

  snapshot(): Record<string, unknown> {
    const memory = process.memoryUsage();
    return {
      relay_connected: this.relayConnected,
      connections_opened_total: this.connectionsOpened,
      sessions_opened_total: this.sessionsOpened,
      active_sessions: this.activeSessions,
      requests_total: this.requests,
      errors_total: this.errors,
      uncertain_outcomes_total: this.uncertainOutcomes,
      latency_average_ms: this.requests ? Math.round(this.latencyTotalMs / this.requests) : 0,
      latency_max_ms: this.latencyMaxMs,
      memory_rss_bytes: memory.rss,
      memory_heap_used_bytes: memory.heapUsed,
    };
  }

  async listen(port: number): Promise<void> {
    if (this.metricsServer) return;
    const server = createServer((request, response) => {
      if (request.method !== "GET" || request.url !== "/metrics") {
        response.writeHead(404).end();
        return;
      }
      response.writeHead(200, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" });
      response.end(JSON.stringify(this.snapshot()));
    });
    await new Promise<void>((resolve, reject) => {
      server.once("error", reject);
      server.listen(port, "127.0.0.1", () => {
        server.off("error", reject);
        resolve();
      });
    });
    this.metricsServer = server;
  }

  async close(): Promise<void> {
    const server = this.metricsServer;
    this.metricsServer = null;
    if (!server) return;
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }
}
