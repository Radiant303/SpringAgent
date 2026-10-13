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
    const previous = this.ws;
    this.ws = null;
    if (previous) previous.close();
    this._failAll(new Error("连接已更换"));
    return new Promise((resolve, reject) => {
      let opened = false;
      const ws = new WebSocket(url);
      this.ws = ws;
      ws.onopen = () => {
        if (this.ws !== ws) { reject(new Error("连接已更换")); return; }
        opened = true;
        resolve();
      };
      ws.onerror = () => {
        if (!opened) reject(new Error("WebSocket 连接失败"));
      };
      ws.onclose = (e) => {
        if (!opened) reject(new Error("连接已断开"));
        if (this.ws !== ws) return;
        this._failAll(new Error("连接已断开"));
        if (this.onClose) this.onClose(e && e.code);
      };
      ws.onmessage = (e) => { if (this.ws === ws) this._onMessage(e.data, ws); };
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

  _onMessage(data, socket = this.ws) {
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
          .then(() => socket === this.ws && socket?.readyState === WebSocket.OPEN
            ? (this.onServerRequest ? this.onServerRequest(msg.method, msg.params || {}) : {}) : {})
          .then((result) => {
            if (socket === this.ws && socket?.readyState === WebSocket.OPEN) this.respond(msg.id, result);
          })
          .catch((err) => {
            if (socket === this.ws && socket?.readyState === WebSocket.OPEN) this.respondError(msg.id, err.message || err);
          });
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
