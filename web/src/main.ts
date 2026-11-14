import { mount } from 'svelte';
import './app.css';
import App from './App.svelte';
import { initLanguage } from './lib/i18n';
import { captureInstallPrompt } from './lib/install';
import { applyTheme, watchSystemTheme } from './lib/theme';

initLanguage();
applyTheme();
watchSystemTheme();
captureInstallPrompt();

mount(App, { target: document.getElementById('app')! });
