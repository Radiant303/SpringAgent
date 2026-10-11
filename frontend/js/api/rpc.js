/**
 * JSON-RPC 2.0 over WebSocket 客户端
 */

export class RpcError extends Error {
  constructor(code, message) {
    super(message);
    this.code = code;
  }
}

export class RpcClient {
  constructor() {
    this.ws = null;
    this.nextId = 1;
    this.pending = new Map();
    this.onNotification = null;   // (method, params) => void
    this.onServerRequest = null;  // (method, params) => Promise<result>
    this.onClose = null;          // (code) => void
  }

  connect(url) {
    return new Promise((resolve, reject) => {
      let opened = false;
      const ws = new WebSocket(url);
      this.ws = ws;
      ws.onopen = () => {
        opened = true;
        resolve();
      };
      ws.onerror = () => {
        if (!opened) reject(new Error("WebSocket 连接失败"));
      };
      ws.onclose = (e) => {
        this._failAll(new Error("连接已断开"));
        if (this.onClose) this.onClose(e && e.code);
      };
      ws.onmessage = (e) => this._onMessage(e.data);
    });
  }

  request(method, params) {
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      try {
        this._send({ jsonrpc: "2.0", id, method, params: params || {} });
      } catch (err) {
        this.pending.delete(id);
        reject(err);
      }
    });
  }

  respond(id, result) {
    this._send({ jsonrpc: "2.0", id, result: result ?? {} });
  }

  respondError(id, message) {
    this._send({ jsonrpc: "2.0", id, error: { code: -32603, message: String(message) } });
  }

  _send(obj) {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      throw new Error("连接未就绪");
    }
    this.ws.send(JSON.stringify(obj));
  }

  _failAll(err) {
    for (const p of this.pending.values()) p.reject(err);
    this.pending.clear();
  }

  _onMessage(data) {
    let msg;
    try {
      msg = JSON.parse(data);
    } catch {
      return;
    }
    if (msg.method !== undefined) {
      if (msg.id !== undefined) {
        // 服务器 → 客户端请求（approval/question），必须应答
        Promise.resolve()
          .then(() => (this.onServerRequest ? this.onServerRequest(msg.method, msg.params || {}) : {}))
          .then((result) => this.respond(msg.id, result))
          .catch((err) => this.respondError(msg.id, (err && err.message) || err));
      } else if (this.onNotification) {
        this.onNotification(msg.method, msg.params || {});
      }
    } else if (msg.id !== undefined) {
      const p = this.pending.get(msg.id);
      if (!p) return;
      this.pending.delete(msg.id);
      if (msg.error) p.reject(new RpcError(msg.error.code, msg.error.message));
      else p.resolve(msg.result);
    }
  }
}

export const rpc = new RpcClient();
