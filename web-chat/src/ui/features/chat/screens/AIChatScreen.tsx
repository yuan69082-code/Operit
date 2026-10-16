import { useEffect, useMemo, useState } from 'react';
import { ChatScreenContent } from '../components/ChatScreenContent';
import { ConfigurationScreen } from './ConfigurationScreen';
import { buildChatFontFaceCss, buildChatThemeStyle } from '../util/chatTheme';
import { useChatViewModel } from '../viewmodel/ChatViewModel';

export function AIChatScreen() {
  const viewModel = useChatViewModel();
  const [updatePage, setUpdatePage] = useState<string | null>(null);
  useEffect(() => {
    if (!viewModel.token || !viewModel.boot?.capabilities.shared_service) return;
    const controller = new AbortController();
    async function checkUpdate() {
      try {
        const response = await fetch('/api/shared/updates', {
          headers: { Authorization: `Bearer ${viewModel.token}` }, signal: controller.signal
        });
        if (!response.ok) throw new Error(`更新检查失败：HTTP ${response.status}`);
        const update = await response.json() as { update_available: boolean; page?: string };
        if (!controller.signal.aborted && update.update_available && typeof update.page === 'string') {
          setUpdatePage(update.page);
        }
      } catch (error) {
        if (!controller.signal.aborted) console.error('shared.update', error);
      }
    }
    void checkUpdate();
    return () => controller.abort();
  }, [viewModel.token, viewModel.boot?.capabilities.shared_service]);
  const fontFaceCss = buildChatFontFaceCss(viewModel.theme);
  const chatThemeStyle = useMemo(() => buildChatThemeStyle(viewModel.theme), [viewModel.theme]);
  const backdropBaseStyle = useMemo(
    () => ({
      background: String(chatThemeStyle['--chat-root-background'] ?? 'transparent')
    }),
    [chatThemeStyle]
  );
  const backdropImageStyle = useMemo(
    () => ({
      backgroundImage: String(chatThemeStyle['--chat-background-image'] ?? 'none'),
      opacity: String(chatThemeStyle['--chat-background-opacity'] ?? '0')
    }),
    [chatThemeStyle]
  );
  const backdropTintStyle = useMemo(
    () => ({
      background: String(chatThemeStyle['--chat-background-tint'] ?? 'transparent')
    }),
    [chatThemeStyle]
  );
  const suggestedUrl = useMemo(() => {
    if (typeof window === 'undefined') {
      return 'http://127.0.0.1:8094/';
    }

    const { protocol, hostname, port } = window.location;
    const resolvedPort = port || '8094';
    return `${protocol}//${hostname}:${resolvedPort}/`;
  }, []);

  return (
    <div
      className={[
        'ai-chat-screen',
        viewModel.activeChatStyle === 'bubble' ? 'chat-style-bubble' : 'chat-style-cursor',
        viewModel.theme?.theme_mode === 'light' ? 'theme-light' : 'theme-dark'
      ].join(' ')}
      style={chatThemeStyle}
    >
      {fontFaceCss ? <style>{fontFaceCss}</style> : null}
      <div
        aria-hidden="true"
        className="chat-glass-backdrop-source"
        style={backdropBaseStyle}
      >
        <div className="chat-glass-backdrop-image" style={backdropImageStyle} />
        <div className="chat-glass-backdrop-tint" style={backdropTintStyle} />
      </div>

      <ChatScreenContent viewModel={viewModel} />
      {updatePage ? (
        <a href={updatePage} target="_blank" rel="noreferrer"
          style={{ position: 'fixed', top: 40, right: 12, zIndex: 600,
            fontSize: 12, color: '#825268', background: '#fffdf9', borderRadius: 8, padding: '4px 8px' }}>
          双端新版本已就绪，查看更新
        </a>
      ) : null}
      {viewModel.boot?.capabilities.shared_service ? (
        <a href="/setup" style={{ position: 'fixed', top: 8, right: 12, zIndex: 600,
          fontSize: 12, color: '#825268', background: '#fffdf9', borderRadius: 8, padding: '4px 8px' }}>
          共享服务设置
        </a>
      ) : null}

      {viewModel.showConnectionOverlay ? (
        <ConfigurationScreen
          error={viewModel.error}
          onCopyUrl={() => {
            if (typeof navigator !== 'undefined' && navigator.clipboard) {
              void navigator.clipboard.writeText(suggestedUrl);
            }
          }}
          onSubmit={viewModel.submitToken}
          onTokenDraftChange={viewModel.setTokenDraft}
          suggestedUrl={suggestedUrl}
          tokenDraft={viewModel.tokenDraft}
        />
      ) : null}
    </div>
  );
}

