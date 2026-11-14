import { mount } from 'svelte';
import './app.css';
import App from './App.svelte';
import { initLanguage } from './lib/i18n';
import { applyTheme, watchSystemTheme } from './lib/theme';

initLanguage();
applyTheme();
watchSystemTheme();

mount(App, { target: document.getElementById('app')! });
