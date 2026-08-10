import { useEffect, useRef, useState } from "react";

// A curated set, not an exhaustive emoji picker — enough to cover the kinds
// of categories/tags this app actually deals with (spending categories,
// trips, hobbies) without pulling in a whole emoji-picker dependency.
const SUGGESTED_EMOJI = [
  "🛒", "⛽", "🍽", "☕", "🍺", "🎮", "💡", "💊", "🚌", "🚗", "🚙", "🚲",
  "✈️", "🏖", "🧳", "🌍", "🏠", "🏥", "💰", "💳", "🔁", "📦", "🛍", "🧾",
  "💼", "🎓", "💻", "📱", "🐶", "👶", "🎉", "🎂", "🎁", "🏋️", "🏃", "⚽",
  "🎵", "📚", "🔧", "🧱", "🎬",
];

// A grab bag of common travel destinations — good fit for trip tags
// specifically, kept as their own group rather than mixed into the list above.
const SUGGESTED_FLAGS = [
  "🇵🇱", "🇵🇹", "🇪🇸", "🇫🇷", "🇮🇹", "🇬🇷", "🇩🇪", "🇬🇧", "🇳🇱", "🇧🇪",
  "🇨🇭", "🇦🇹", "🇨🇿", "🇭🇷", "🇸🇮", "🇭🇺", "🇮🇪", "🇮🇸", "🇳🇴", "🇸🇪",
  "🇩🇰", "🇫🇮", "🇹🇷", "🇪🇬", "🇦🇪", "🇺🇸", "🇨🇦", "🇲🇽", "🇯🇵", "🇹🇭",
];

interface Props {
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  className?: string;
}

export function EmojiInput({ value, onChange, placeholder, className }: Props) {
  const [open, setOpen] = useState(false);
  const wrapperRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    function onClickOutside(e: MouseEvent) {
      if (wrapperRef.current && !wrapperRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    }
    document.addEventListener("mousedown", onClickOutside);
    return () => document.removeEventListener("mousedown", onClickOutside);
  }, [open]);

  return (
    <div ref={wrapperRef} className="relative">
      <div className="flex items-center gap-1">
        <input
          type="text"
          value={value}
          onChange={(e) => onChange(e.target.value)}
          placeholder={placeholder}
          className={className}
        />
        <button
          type="button"
          onClick={() => setOpen((o) => !o)}
          title="Pick an emoji"
          className="shrink-0 rounded-md border border-neutral-300 bg-white px-2 py-1.5 text-sm hover:bg-neutral-50"
        >
          🙂
        </button>
      </div>
      {open && (
        <div className="absolute right-0 top-full z-20 mt-1 w-64 rounded-md border border-neutral-200 bg-white p-2 shadow-lg">
          <EmojiGrid emojis={SUGGESTED_EMOJI} onPick={(e) => { onChange(e); setOpen(false); }} />
          <p className="mb-1 mt-2 px-1 text-[10px] font-semibold uppercase tracking-wide text-neutral-400">
            Trips
          </p>
          <EmojiGrid emojis={SUGGESTED_FLAGS} onPick={(e) => { onChange(e); setOpen(false); }} />
        </div>
      )}
    </div>
  );
}

function EmojiGrid({ emojis, onPick }: { emojis: string[]; onPick: (emoji: string) => void }) {
  return (
    <div className="grid grid-cols-8 gap-0.5">
      {emojis.map((emoji) => (
        <button
          key={emoji}
          type="button"
          onClick={() => onPick(emoji)}
          className="rounded p-1 text-lg hover:bg-neutral-100"
        >
          {emoji}
        </button>
      ))}
    </div>
  );
}
