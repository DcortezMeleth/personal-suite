import { useState } from "react";
import { useBackdropDismiss } from "@delfin/ui";
import type { Tag, TagForm } from "../api/client";
import { EmojiInput } from "./EmojiInput";

interface Props {
  initial?: Tag;
  onSave: (form: TagForm) => void;
  onClose: () => void;
}

export function TagFormModal({ initial, onSave, onClose }: Props) {
  const [form, setForm] = useState<TagForm>({
    name: initial?.name ?? "",
    color: initial?.color ?? "#64748b",
    icon: initial?.icon ?? "",
  });

  function set<K extends keyof TagForm>(k: K, v: TagForm[K]) {
    setForm((f) => ({ ...f, [k]: v }));
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    onSave({ ...form, icon: form.icon?.trim() || null });
  }

  const backdrop = useBackdropDismiss(onClose);
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40" {...backdrop}>
      <div className="w-full max-w-md rounded-xl bg-white p-6 shadow-xl">
        <h2 className="mb-4 text-lg font-semibold text-neutral-900">{initial ? "Edit Tag" : "New Tag"}</h2>
        <form onSubmit={handleSubmit} className="space-y-3">
          <Field label="Name">
            <input
              required
              type="text"
              value={form.name}
              onChange={(e) => set("name", e.target.value)}
              className={inputCls}
              placeholder="e.g. Portugal 2026"
            />
          </Field>

          <div className="grid grid-cols-2 gap-3">
            <Field label="Color">
              <div className="flex items-center gap-2">
                <input
                  type="color"
                  value={form.color}
                  onChange={(e) => set("color", e.target.value)}
                  className="h-8 w-10 shrink-0 cursor-pointer rounded border border-neutral-300"
                />
                <input
                  type="text"
                  value={form.color}
                  onChange={(e) => set("color", e.target.value)}
                  className={inputCls}
                />
              </div>
            </Field>
            <Field label="Icon (emoji)">
              <EmojiInput
                value={form.icon ?? ""}
                onChange={(v) => set("icon", v)}
                className={inputCls}
                placeholder="🏖"
              />
            </Field>
          </div>

          <div className="flex justify-end gap-3 pt-2">
            <button
              type="button"
              onClick={onClose}
              className="rounded-md border border-neutral-300 px-4 py-2 text-sm font-medium text-neutral-700 hover:bg-neutral-50"
            >
              Cancel
            </button>
            <button
              type="submit"
              className="rounded-md bg-primary-600 px-4 py-2 text-sm font-medium text-white hover:bg-primary-700"
            >
              {initial ? "Save" : "Create"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

const inputCls = "w-full rounded-md border border-neutral-300 px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500";

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <label className="mb-1 block text-xs font-medium text-neutral-600">{label}</label>
      {children}
    </div>
  );
}
