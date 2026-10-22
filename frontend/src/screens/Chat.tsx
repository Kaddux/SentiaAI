import { useCallback, useRef, useState } from "react";
import { api } from "../api";
import { useVoice } from "../hooks/useVoice";
import StreamingText from "../components/StreamingText";
import ReplyInsight, { topicFromTools, type InsightTopic } from "../components/ReplyInsight";

type Msg = { role: "user" | "assistant" | "tool"; text: string; insight?: InsightTopic | null };

function MicIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <rect x="9" y="2" width="6" height="12" rx="3" />
      <path d="M5 10v2a7 7 0 0 0 14 0v-2" />
      <line x1="12" y1="19" x2="12" y2="22" />
    </svg>
  );
}

function SpeakerIcon({ muted }: { muted: boolean }) {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M11 5 6 9H2v6h4l5 4V5z" />
      {muted ? (
        <>
          <line x1="22" y1="9" x2="16" y2="15" />
          <line x1="16" y1="9" x2="22" y2="15" />
        </>
      ) : (
        <>
          <path d="M15.5 8.5a5 5 0 0 1 0 7" />
          <path d="M19 5a10 10 0 0 1 0 14" />
        </>
      )}
    </svg>
  );
}

function Wave({ bars = 7, className }: { bars?: number; className: string }) {
  return (
    <span className={className} aria-hidden="true">
      {Array.from({ length: bars }).map((_, i) => (
        <i key={i} style={{ animationDelay: `${i * 0.09}s` }} />
      ))}
    </span>
  );
}

export default function Chat() {
  const [messages, setMessages] = useState<Msg[]>([
    { role: "assistant", text: "Hi! Ask me about your sleep, heart rate, activity, or reminders. You can also tap the mic and just talk." },
  ]);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [conv, setConv] = useState<number | null>(null);
  const scroller = useRef<HTMLDivElement>(null);
  const sendRef = useRef<(textOverride?: string) => void>(() => {});

  const scrollToEnd = useCallback(() => {
    const el = scroller.current;
    if (!el) return;
    const smooth = !window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    el.scrollTo({ top: el.scrollHeight, behavior: smooth ? "smooth" : "auto" });
  }, []);

  const voice = useVoice({
    onFinal: (text) => sendRef.current(text),
    onError: (msg) => setMessages((m) => [...m, { role: "assistant", text: msg }]),
  });

  async function send(textOverride?: string) {
    const text = (textOverride ?? input).trim();
    if (!text || busy) return;
    setInput("");
    setMessages((m) => [...m, { role: "user", text }]);
    setBusy(true);
    setTimeout(scrollToEnd, 40);
    try {
      const res = await api.chat(text, conv);
      setConv(res.conversation_id);
      const made = ((res as any).tool_calls_made || []) as string[];
      if (made.length) {
        setMessages((m) => [...m, { role: "tool", text: `tool calls: ${made.join(", ")}` }]);
      }
      const reply = res.message.content;
      setMessages((m) => [...m, { role: "assistant", text: reply, insight: topicFromTools(made) }]);
      voice.speak(reply);
    } catch (e: any) {
      setMessages((m) => [...m, { role: "assistant", text: `Sorry — ${e.message}` }]);
    } finally {
      setBusy(false);
      setTimeout(scrollToEnd, 40);
    }
  }
  sendRef.current = send;

  return (
    <>
      <h2 className="section-title">
        Chat with your <span className="hl">assistant</span>
      </h2>
      <div className="card">
        <div className="chat" ref={scroller}>
          {messages.map((m, i) => (
            <div className={`msg ${m.role}`} key={i}>
              {m.role === "assistant" ? (
                <StreamingText text={m.text} onTick={scrollToEnd} />
              ) : (
                m.text
              )}
              {m.insight && <ReplyInsight topic={m.insight} />}
            </div>
          ))}
          {busy && (
            <div className="msg tool typing" aria-label="assistant is thinking">
              <span />
              <span />
              <span />
            </div>
          )}
        </div>

        <div className="composer">
          <button
            className={`mic-btn ${voice.listening ? "listening" : ""}`}
            onClick={() => {
              if (voice.listening) {
                voice.stopListening();
              } else {
                voice.cancelSpeech();
                voice.startListening();
              }
            }}
            disabled={busy || voice.transcribing || !voice.sttSupported}
            aria-label={voice.listening ? "Stop recording" : "Start recording"}
            title={
              !voice.sttSupported
                ? "Voice input needs Chrome or Edge"
                : voice.listening
                ? "Tap to stop and send"
                : "Tap to talk"
            }
          >
            <MicIcon />
          </button>

          <input
            value={input}
            placeholder={
              voice.listening
                ? "Recording… tap the mic to stop and send"
                : voice.transcribing
                ? "Transcribing…"
                : "How was my sleep last night?"
            }
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && send()}
            disabled={busy}
          />

          {voice.listening && <Wave className="voice-wave" />}

          <button className="btn" onClick={() => send()} disabled={busy || voice.listening || voice.transcribing}>
            Send
          </button>

          <button
            className={`icon-btn ${voice.muted ? "muted" : ""}`}
            onClick={voice.toggleMute}
            disabled={!voice.ttsSupported}
            aria-label={voice.muted ? "Unmute spoken replies" : "Mute spoken replies"}
            title={voice.muted ? "Spoken replies are off" : "Spoken replies are on"}
          >
            <SpeakerIcon muted={voice.muted} />
          </button>
        </div>

        {voice.sttSupported && voice.devices.length > 0 && (
          <div className="mic-select-row">
            <span className="muted">Microphone</span>
            <select
              className="mic-select"
              value={voice.deviceId}
              onChange={(e) => voice.setDeviceId(e.target.value)}
              disabled={busy || voice.listening || voice.transcribing}
            >
              {voice.devices.map((d, i) => (
                <option key={d.deviceId || i} value={d.deviceId}>
                  {d.label || `Microphone ${i + 1}`}
                </option>
              ))}
            </select>
            <span className="muted">If the level stays at 0, pick another device.</span>
          </div>
        )}

        {voice.listening && (
          <div className="interim-hint">
            Recording… {voice.seconds}s — tap the mic to stop and send.
          </div>
        )}
        {voice.listening && (
          <div className="level-meter" aria-hidden="true">
            <span style={{ width: `${voice.level}%` }} />
          </div>
        )}
        {voice.transcribing && <div className="interim-hint">Transcribing…</div>}
        {voice.speaking && (
          <div className="speaking-row">
            <Wave bars={5} className="eq" />
            <span>Spoken reply</span>
          </div>
        )}
        {!voice.sttSupported && (
          <div className="error" style={{ marginTop: 8 }}>
            {!voice.secureContext
              ? "Microphone needs a secure page — open http://localhost:5173 on this PC (or serve over HTTPS)."
              : "Voice input isn't available in this browser. Try Chrome or Edge."}
          </div>
        )}
      </div>
      <div className="footer-note">
        Grounded in structured health signals · non-diagnostic · never raw sensor data
      </div>
    </>
  );
}
