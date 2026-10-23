import { useCallback, useEffect, useRef, useState } from "react";

/** Clean assistant text for speech: no markdown, emojis, URLs, or "slash". */
export function stripForSpeech(text: string): string {
  return (
    text
      // code blocks / inline code
      .replace(/```[\s\S]*?```/g, " ")
      .replace(/`([^`]*)`/g, "$1")
      // markdown links/images -> keep the label
      .replace(/!\[([^\]]*)\]\([^)]*\)/g, "$1")
      .replace(/\[([^\]]+)\]\([^)]*\)/g, "$1")
      // URLs / emails (don't read "h t t p s colon slash slash")
      .replace(/https?:\/\/\S+/gi, " ")
      .replace(/\bwww\.\S+/gi, " ")
      .replace(/[\w.+-]+@[\w-]+\.[\w.-]+/g, " ")
      // emojis, pictographs, flags, skin tones, keycaps, ZWJ/variation selectors
      .replace(
        /[\p{Extended_Pictographic}\u{1F1E6}-\u{1F1FF}\u{1F3FB}-\u{1F3FF}\u{FE0F}\u{200D}\u{20E3}]/gu,
        " "
      )
      // markdown emphasis / headings / blockquotes / tables
      .replace(/[*_#>~|]/g, " ")
      // slashes & backslashes would be spoken as "slash"
      .replace(/[\\/]/g, " ")
      // brackets/braces/angle brackets
      .replace(/[\[\]{}<>]/g, " ")
      // ampersand
      .replace(/&/g, " and ")
      // leading bullets
      .replace(/^\s*[-•]\s+/gm, " ")
      // tidy punctuation & whitespace
      .replace(/\s+([,.;:!?])/g, "$1")
      .replace(/([,.;:!?])\1+/g, "$1")
      .replace(/\s+/g, " ")
      .trim()
  );
}

function defaultVoiceName(all: SpeechSynthesisVoice[]): string {
  const en = all.filter((v) => v.lang?.toLowerCase().startsWith("en"));
  const pool = en.length ? en : all;
  const preferred = [
    "Google US English",
    "Microsoft Aria",
    "Samantha",
    "Microsoft Zira",
    "Google UK English Female",
  ];
  for (const p of preferred) {
    const match = pool.find((v) => v.name.includes(p));
    if (match) return match.name;
  }
  return pool[0]?.name ?? "";
}

function pickMimeType(): string {
  if (typeof MediaRecorder === "undefined") return "";
  const candidates = [
    "audio/webm;codecs=opus",
    "audio/webm",
    "audio/ogg;codecs=opus",
    "audio/mp4",
  ];
  return candidates.find((m) => MediaRecorder.isTypeSupported(m)) ?? "";
}

async function transcribeBlob(blob: Blob): Promise<string> {
  const token = localStorage.getItem("sentia.token");
  const form = new FormData();
  form.append("file", blob, "speech.webm");
  const res = await fetch("/api/assistant/transcribe", {
    method: "POST",
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
    body: form,
  });
  if (!res.ok) {
    let detail = `${res.status}`;
    try {
      const j = await res.json();
      detail = j.detail || JSON.stringify(j);
    } catch {
      /* ignore */
    }
    throw new Error(`Transcription failed (${detail})`);
  }
  const j = await res.json();
  return (j?.text || "").trim();
}

export function useVoice({
  onFinal,
  onError,
}: {
  onFinal: (text: string) => void;
  onError?: (message: string) => void;
}) {
  const onFinalRef = useRef(onFinal);
  const onErrorRef = useRef(onError);
  onFinalRef.current = onFinal;
  onErrorRef.current = onError;

  const [listening, setListening] = useState(false);
  const [transcribing, setTranscribing] = useState(false);
  const [seconds, setSeconds] = useState(0);
  const [level, setLevel] = useState(0);
  const [speaking, setSpeaking] = useState(false);
  const [muted, setMuted] = useState(false);
  const [devices, setDevices] = useState<MediaDeviceInfo[]>([]);
  const [deviceId, setDeviceId] = useState("");
  const [voices, setVoices] = useState<SpeechSynthesisVoice[]>([]);
  const [voiceName, setVoiceName] = useState("");

  const recorderRef = useRef<MediaRecorder | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const startedAtRef = useRef(0);
  const timerRef = useRef<number | undefined>(undefined);
  const audioCtxRef = useRef<AudioContext | null>(null);
  const analyserRef = useRef<AnalyserNode | null>(null);
  const levelTimerRef = useRef<number | undefined>(undefined);
  const levelRef = useRef(0);
  const stateRef = useRef({ muted, voices, voiceName });
  stateRef.current = { muted, voices, voiceName };

  const ttsSupported = typeof window !== "undefined" && "speechSynthesis" in window;
  const secureContext = typeof window !== "undefined" ? window.isSecureContext : false;
  const sttSupported =
    secureContext &&
    typeof navigator !== "undefined" &&
    !!navigator.mediaDevices?.getUserMedia &&
    typeof MediaRecorder !== "undefined";

  // ---- speech synthesis (TTS) ----
  useEffect(() => {
    if (!ttsSupported) return;
    const load = () => {
      const all = window.speechSynthesis.getVoices();
      if (!all.length) return;
      setVoices(all);
      setVoiceName((cur) => cur || defaultVoiceName(all));
    };
    load();
    window.speechSynthesis.addEventListener?.("voiceschanged", load);
    return () => window.speechSynthesis.removeEventListener?.("voiceschanged", load);
  }, [ttsSupported]);

  const cancelSpeech = useCallback(() => {
    if (ttsSupported) window.speechSynthesis.cancel();
    setSpeaking(false);
  }, [ttsSupported]);

  const stopMeter = useCallback(() => {
    if (levelTimerRef.current) {
      window.clearInterval(levelTimerRef.current);
      levelTimerRef.current = undefined;
    }
    try {
      analyserRef.current?.disconnect();
    } catch {
      /* ignore */
    }
    analyserRef.current = null;
    if (audioCtxRef.current) {
      audioCtxRef.current.close().catch(() => {});
      audioCtxRef.current = null;
    }
    levelRef.current = 0;
    setLevel(0);
  }, []);

  const refreshDevices = useCallback(async () => {
    if (!navigator.mediaDevices?.enumerateDevices) return;
    try {
      const all = await navigator.mediaDevices.enumerateDevices();
      const inputs = all.filter((d) => d.kind === "audioinput");
      setDevices(inputs);
      setDeviceId((prev) => {
        if (prev && inputs.some((d) => d.deviceId === prev)) return prev;
        return inputs.find((d) => d.deviceId === "default")?.deviceId || inputs[0]?.deviceId || "";
      });
    } catch {
      /* ignore */
    }
  }, []);

  useEffect(() => {
    if (!sttSupported) return;
    refreshDevices();
    navigator.mediaDevices?.addEventListener?.("devicechange", refreshDevices);
    return () => navigator.mediaDevices?.removeEventListener?.("devicechange", refreshDevices);
  }, [sttSupported, refreshDevices]);

  const stopListening = useCallback(() => {
    try {
      recorderRef.current?.stop();
    } catch {
      /* ignore */
    }
  }, []);

  const startListening = useCallback(async () => {
    if (!sttSupported || listening || transcribing) return;
    cancelSpeech();
    try {
      const audio: MediaTrackConstraints | boolean =
        deviceId && deviceId !== "default" ? { deviceId: { exact: deviceId } } : true;
      const stream = await navigator.mediaDevices.getUserMedia({ audio });
      streamRef.current = stream;
      refreshDevices(); // labels become available after the permission grant
      // Live mic level, so a muted/wrong input device is obvious.
      try {
        const ctx = new AudioContext();
        await ctx.resume();
        const src = ctx.createMediaStreamSource(stream);
        const analyser = ctx.createAnalyser();
        analyser.fftSize = 512;
        src.connect(analyser);
        audioCtxRef.current = ctx;
        analyserRef.current = analyser;
        const data = new Uint8Array(analyser.fftSize);
        levelTimerRef.current = window.setInterval(() => {
          analyser.getByteTimeDomainData(data);
          let sum = 0;
          for (let i = 0; i < data.length; i++) {
            const v = (data[i] - 128) / 128;
            sum += v * v;
          }
          const rms = Math.sqrt(sum / data.length);
          const lvl = Math.min(100, Math.round(rms * 400));
          levelRef.current = lvl;
          setLevel(lvl);
        }, 100);
      } catch {
        /* metering is optional */
      }
      const mime = pickMimeType();
      const rec = mime ? new MediaRecorder(stream, { mimeType: mime }) : new MediaRecorder(stream);
      chunksRef.current = [];
      rec.ondataavailable = (e) => {
        if (e.data.size) chunksRef.current.push(e.data);
      };
      rec.onstop = async () => {
        if (timerRef.current) {
          window.clearInterval(timerRef.current);
          timerRef.current = undefined;
        }
        stopMeter();
        const durationMs = Date.now() - startedAtRef.current;
        setSeconds(0);
        stream.getTracks().forEach((t) => t.stop());
        streamRef.current = null;
        recorderRef.current = null;
        setListening(false);
        const blob = new Blob(chunksRef.current, { type: rec.mimeType || "audio/webm" });
        chunksRef.current = [];
        if (!blob.size) {
          onErrorRef.current?.(
            "The microphone captured no audio. Check the input device and permissions."
          );
          return;
        }
        if (durationMs < 400) {
          onErrorRef.current?.(
            "That was too short — hold the mic, say a sentence, then release."
          );
          return;
        }
        setTranscribing(true);
        try {
          const text = await transcribeBlob(blob);
          if (text) {
            onFinalRef.current(text);
          } else {
            onErrorRef.current?.("I didn't catch any words. Check the microphone and try again.");
          }
        } catch (err: any) {
          onErrorRef.current?.(err?.message || "Transcription failed.");
        } finally {
          setTranscribing(false);
        }
      };
      recorderRef.current = rec;
      rec.start(250);
      startedAtRef.current = Date.now();
      setSeconds(0);
      if (timerRef.current) window.clearInterval(timerRef.current);
      timerRef.current = window.setInterval(() => {
        setSeconds(Math.floor((Date.now() - startedAtRef.current) / 1000));
      }, 250);
      setListening(true);
    } catch (err: any) {
      const name = err?.name;
      onErrorRef.current?.(
        name === "NotAllowedError" || name === "SecurityError"
          ? "Microphone access was blocked. Allow the mic in your browser's site settings (and use localhost or HTTPS)."
          : name === "NotFoundError"
          ? "No microphone was found. Plug one in and try again."
          : name === "NotReadableError"
          ? "The microphone is busy in another app. Close it and try again."
          : "Could not start the microphone."
      );
    }
  }, [sttSupported, listening, transcribing, cancelSpeech, stopMeter, deviceId, refreshDevices]);

  // Stop the mic / release tracks if the component unmounts mid-recording.
  useEffect(() => {
    return () => {
      if (timerRef.current) window.clearInterval(timerRef.current);
      stopMeter();
      try {
        recorderRef.current?.stop();
      } catch {
        /* ignore */
      }
      streamRef.current?.getTracks().forEach((t) => t.stop());
    };
  }, [stopMeter]);

  const speak = useCallback(
    (text: string) => {
      if (!ttsSupported) return;
      const clean = stripForSpeech(text);
      if (!clean) return;
      const { muted: isMuted, voices: all, voiceName: name } = stateRef.current;
      if (isMuted) return;
      window.speechSynthesis.cancel();
      const u = new SpeechSynthesisUtterance(clean);
      const v =
        all.find((x) => x.name === name) ||
        all.find((x) => x.lang?.toLowerCase().startsWith("en"));
      if (v) {
        u.voice = v;
        u.lang = v.lang;
      }
      u.rate = 1.0;
      u.pitch = 1.0;
      u.volume = 1.0;
      u.onstart = () => setSpeaking(true);
      u.onend = () => setSpeaking(false);
      u.onerror = () => setSpeaking(false);
      window.speechSynthesis.speak(u);
    },
    [ttsSupported]
  );

  const toggleMute = useCallback(() => {
    setMuted((m) => {
      const next = !m;
      if (next) {
        window.speechSynthesis?.cancel();
        setSpeaking(false);
      }
      return next;
    });
  }, []);

  return {
    sttSupported,
    ttsSupported,
    secureContext,
    devices,
    deviceId,
    setDeviceId,
    listening,
    transcribing,
    seconds,
    level,
    speaking,
    muted,
    voices,
    voiceName,
    setVoiceName,
    startListening,
    stopListening,
    speak,
    cancelSpeech,
    toggleMute,
  };
}
