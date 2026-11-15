'use strict';

// id, name, dark (tells web pages whether to use their dark style), and the UI colours.
const T = (id, name, dark, bg, tab, active, fg, muted, line, accent) =>
  ({ id, name, dark, bg, tab, active, fg, muted, line, accent });

const PRESETS = [
  T('mbrowser',        'mbrowser',        true,  '#0a0e1f', '#131a33', '#1c2548', '#eaf0ff', '#8f9bc2', '#243056', '#5b8cff'),
  T('midnight',        'Midnight',        true,  '#1e1f22', '#2b2d31', '#3a3d44', '#e6e6e6', '#9aa0a6', '#34363b', '#7aa2f7'),
  T('daylight',        'Daylight',        false, '#e4e7ec', '#d3d7de', '#ffffff', '#1f2328', '#656d76', '#c4c9d1', '#3b82f6'),
  T('nord',            'Nord',            true,  '#2e3440', '#3b4252', '#434c5e', '#eceff4', '#a3adc0', '#4c566a', '#88c0d0'),
  T('dracula',         'Dracula',         true,  '#282a36', '#343746', '#44475a', '#f8f8f2', '#a0a4c0', '#44475a', '#bd93f9'),
  T('gruvbox',         'Gruvbox',         true,  '#282828', '#3c3836', '#504945', '#ebdbb2', '#a89984', '#504945', '#fabd2f'),
  T('solarized-dark',  'Solarized Dark',  true,  '#002b36', '#073642', '#0d4452', '#eee8d5', '#93a1a1', '#0f4c5c', '#2aa1e8'),
  T('solarized-light', 'Solarized Light', false, '#eee8d5', '#e3dcc5', '#fdf6e3', '#073642', '#657b83', '#d3cbb2', '#268bd2'),
  T('forest',          'Forest',          true,  '#1a2421', '#24332e', '#2f4139', '#e3efe8', '#92ab9f', '#2c3d36', '#6fcf97'),
  T('ocean',           'Ocean',           true,  '#0d1b2a', '#14263a', '#1c3550', '#e0ecf7', '#8aa3bd', '#1f3a56', '#38bdf8'),
  T('rose',            'Rose',            true,  '#1f1a1d', '#2c2328', '#3a2d34', '#f3e6ec', '#b496a3', '#3a2d34', '#f472b6'),
  T('sand',            'Sand',            false, '#ece5d8', '#ddd4c3', '#faf6ee', '#2b2620', '#7a6f5f', '#cfc5b1', '#c2410c'),
  T('slate',           'Slate',           true,  '#17191e', '#22252c', '#2c303a', '#d9dde8', '#828899', '#2e3240', '#5579d6'),
  T('contrast',        'High contrast',   true,  '#000000', '#111111', '#222222', '#ffffff', '#cccccc', '#777777', '#ffe600'),
];

const findTheme = (id, custom = []) => [...PRESETS, ...(custom || [])].find((t) => t.id === id);

// black or white text, whichever reads better on the given background colour
function onColor(hex) {
  const m = /^#?([0-9a-f]{6})$/i.exec(String(hex || ''));
  if (!m) return '#000000';
  const n = parseInt(m[1], 16);
  const lin = (c) => { c /= 255; return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; };
  const L = 0.2126 * lin((n >> 16) & 255) + 0.7152 * lin((n >> 8) & 255) + 0.0722 * lin(n & 255);
  return L > 0.35 ? '#000000' : '#ffffff';
}

const isColor = (c) => /^#[0-9a-f]{6}$/i.test(String(c || ''));

module.exports = { PRESETS, findTheme, onColor, isColor };
