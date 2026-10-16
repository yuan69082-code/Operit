const fs = require('node:fs');
const path = require('node:path');
const { randomUUID, randomBytes } = require('node:crypto');

class Store {
  constructor(directory) {
    this.directory = directory;
    fs.mkdirSync(directory, { recursive: true });
    this.file = path.join(directory, 'shared-chat.json');
    if (fs.existsSync(this.file)) {
      this.state = JSON.parse(fs.readFileSync(this.file, 'utf8'));
      if (this.state.schema !== 1) throw new Error('Unsupported shared-chat schema');
    } else {
      this.state = { schema: 1, token: randomBytes(32).toString('hex'), revision: 0,
        config: { endpoint: '', apiKey: '', model: '', name: 'Operit', prompt: '', tools: false },
        chats: [], currentChatId: null };
      this.save();
    }
    // A stopped process cannot retain a streaming lease after restart.
    for (const chat of this.state.chats) {
      for (const message of chat.messages) message.streaming = false;
    }
    this.save();
  }
  save() {
    this.state.revision++;
    const temp = this.file + '.tmp';
    fs.writeFileSync(temp, JSON.stringify(this.state), { mode: 0o600 });
    fs.renameSync(temp, this.file);
  }
  chat(id) {
    const chat = this.state.chats.find(item => item.id === id);
    if (!chat) throw Object.assign(new Error('会话不存在'), { status: 404 });
    return chat;
  }
  create(input = {}) {
    const chat = { id: randomUUID(), title: input.title || '新对话', group: input.group || null,
      character_card_name: this.state.config.name, character_group_id: null, locked: false,
      updated_at: Date.now(), messages: [], modelMessages: [] };
    this.state.chats.unshift(chat);
    this.state.currentChatId = chat.id;
    this.save();
    return chat;
  }
  message(chat, sender, content, extra = {}) {
    const timestamp = Math.max(Date.now(), (chat.messages.at(-1)?.timestamp || 0) + 1);
    const message = { id: randomUUID(), sender, content_raw: content, timestamp,
      attachments: [], streaming: false, ...extra };
    chat.messages.push(message);
    chat.updated_at = timestamp;
    this.save();
    return message;
  }
  summary(chat, busy = false) {
    const { messages, modelMessages, ...summary } = chat;
    return { ...summary, active_streaming: busy };
  }
}
module.exports = { Store };
