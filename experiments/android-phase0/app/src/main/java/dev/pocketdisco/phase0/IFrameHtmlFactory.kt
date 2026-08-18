package dev.pocketdisco.phase0

object IFrameHtmlFactory {
    fun create(origin: String): String {
        val normalizedOrigin = ProbeInput.webOrigin(origin)
        return HTML.replace("__ORIGIN__", JsonString.quote(normalizedOrigin))
    }

    private val HTML = """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>YouTube foreground probe</title>
          <style>
            html, body { margin: 0; padding: 0; background: #000; color: #fff; font-family: sans-serif; }
            #player { width: 100%; height: 270px; min-width: 200px; min-height: 200px; }
            #ready { display: block; width: 100%; min-height: 48px; margin: 8px 0 0; font-size: 16px; }
          </style>
        </head>
        <body>
          <div id="player"></div>
          <button id="ready" type="button" disabled>Ready to play</button>
          <script>
            const configuredOrigin = __ORIGIN__;
            let player = null;
            let iframeReady = false;
            let explicitReady = false;
            let lastVideoId = '';
            let lastPlaylistIndex = -1;

            function report(name, detail) {
              const payload = Object.assign({
                page_monotonic_ms: performance.now(),
                visibility: document.visibilityState
              }, detail || {});
              try {
                window.PocketDiscoBridge.onEvent(name, JSON.stringify(payload));
              } catch (_) {
              }
            }

            function updateReadyButton() {
              const button = document.getElementById('ready');
              button.disabled = !iframeReady;
              button.textContent = explicitReady ? 'Ready confirmed' : 'Ready to play';
            }

            function resetReadiness(reason) {
              explicitReady = false;
              updateReadyButton();
              report('readiness_reset', { reason });
            }

            function observeItem(reason) {
              if (!player || !iframeReady) return;
              const data = player.getVideoData() || {};
              const videoId = data.video_id || '';
              const playlistIndex = player.getPlaylistIndex();
              if (videoId && (videoId !== lastVideoId || playlistIndex !== lastPlaylistIndex)) {
                report(lastVideoId ? 'playlist_transition' : 'video_loaded', {
                  reason,
                  from_video_id: lastVideoId,
                  to_video_id: videoId,
                  from_playlist_index: lastPlaylistIndex,
                  to_playlist_index: playlistIndex
                });
                lastVideoId = videoId;
                lastPlaylistIndex = playlistIndex;
              }
            }

            document.getElementById('ready').addEventListener('click', function () {
              if (!player || !iframeReady) return;
              explicitReady = true;
              updateReadyButton();
              report('user_ready_gesture', {});
              player.playVideo();
            });

            window.onYouTubeIframeAPIReady = function () {
              player = new YT.Player('player', {
                width: '480',
                height: '270',
                playerVars: {
                  controls: 1,
                  enablejsapi: 1,
                  origin: configuredOrigin,
                  playsinline: 1
                },
                events: {
                  onReady: function () {
                    iframeReady = true;
                    updateReadyButton();
                    report('iframe_ready', {});
                  },
                  onStateChange: function (event) {
                    report('player_state', { state: event.data });
                    observeItem('state_' + event.data);
                  },
                  onPlaybackQualityChange: function (event) {
                    report('playback_quality', { quality: event.data });
                  },
                  onError: function (event) {
                    report('player_error', { code: event.data });
                  },
                  onAutoplayBlocked: function () {
                    explicitReady = false;
                    updateReadyButton();
                    report('autoplay_blocked', {});
                  }
                }
              });
            };

            window.phase0 = {
              cueVideo: function (videoId) {
                if (!player || !iframeReady) return;
                resetReadiness('cue_video');
                lastVideoId = '';
                lastPlaylistIndex = -1;
                player.cueVideoById({ videoId: videoId, startSeconds: 0 });
                report('cue_video_requested', { video_id: videoId });
              },
              cuePlaylist: function (playlistId) {
                if (!player || !iframeReady) return;
                resetReadiness('cue_playlist');
                lastVideoId = '';
                lastPlaylistIndex = -1;
                player.cuePlaylist({ listType: 'playlist', list: playlistId, index: 0, startSeconds: 0 });
                report('cue_playlist_requested', { playlist_id: playlistId });
              },
              play: function () {
                if (!player || !iframeReady) return;
                if (!explicitReady) {
                  report('play_blocked_not_ready', {});
                  return;
                }
                player.playVideo();
                report('play_requested', {});
              },
              pause: function (reason) {
                if (!player || !iframeReady) return;
                player.pauseVideo();
                report('pause_requested', { reason: reason || 'manual' });
              },
              seek: function (seconds) {
                if (!player || !iframeReady) return;
                player.seekTo(seconds, true);
                report('seek_requested', { seconds });
              }
            };

            setInterval(function () {
              if (!player || !iframeReady) return;
              report('position_sample', {
                seconds: player.getCurrentTime(),
                duration_seconds: player.getDuration(),
                state: player.getPlayerState(),
                playlist_index: player.getPlaylistIndex()
              });
              observeItem('timer');
            }, 1000);

            const apiScript = document.createElement('script');
            apiScript.src = 'https://www.youtube.com/iframe_api';
            document.head.appendChild(apiScript);
          </script>
        </body>
        </html>
    """.trimIndent()
}
