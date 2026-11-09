const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('operitPhone', { open: address => ipcRenderer.invoke('operit-open-phone', address) });
