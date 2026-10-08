import { useState, type ReactNode } from "react";
import { ConfirmDialog } from "@delfin/ui";

interface Pending {
  message: string;
  onConfirm: () => void;
  title: string;
  confirmLabel: string;
}

export interface ConfirmOptions {
  title?: string;
  confirmLabel?: string;
}

/**
 * Asks before doing something destructive.
 *
 * Deleting a teacher takes their blocked hours and subject assignments with
 * them; deleting a class will later take its whole teaching allocation. With
 * eighty teachers and fifty classes to maintain, a misplaced click on a row's
 * last column should not be silently final.
 *
 * Uses the shared ConfirmDialog rather than window.confirm, which browsers let
 * users suppress — after which it returns false and the delete silently stops
 * working instead.
 */
export function useConfirm(): [
  ReactNode,
  (message: string, onConfirm: () => void, options?: ConfirmOptions) => void,
] {
  const [pending, setPending] = useState<Pending | null>(null);

  const dialog = pending ? (
    <ConfirmDialog
      title={pending.title}
      message={pending.message}
      confirmLabel={pending.confirmLabel}
      cancelLabel="Anuluj"
      onConfirm={() => {
        pending.onConfirm();
        setPending(null);
      }}
      onCancel={() => setPending(null)}
    />
  ) : null;

  // Deleting is the common case, so it stays the default — but an import also
  // needs confirming, and asking "confirm deletion / delete" there would
  // describe the wrong action.
  return [
    dialog,
    (message, onConfirm, options) =>
      setPending({
        message,
        onConfirm,
        title: options?.title ?? "Potwierdź usunięcie",
        confirmLabel: options?.confirmLabel ?? "Usuń",
      }),
  ];
}
