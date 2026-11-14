// Preload for the profile chooser / unlock window that appears before the browser opens
const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('gate', {
  list: () => ipcRenderer.invoke('gate:list'),
  choose: (id, password, remember) => ipcRenderer.invoke('gate:choose', id, password, remember),
  cancel: () => ipcRenderer.send('gate:cancel'),
});
