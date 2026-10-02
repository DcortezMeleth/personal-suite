import { useState, type ReactNode } from "react";
import { ConfirmDialog } from "@delfin/ui";

interface Pending {
  message: string;
  onConfirm: () => void;
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
export function useConfirm(): [ReactNode, (message: string, onConfirm: () => void) => void] {
  const [pending, setPending] = useState<Pending | null>(null);

  const dialog = pending ? (
    <ConfirmDialog
      title="Potwierdź usunięcie"
      message={pending.message}
      confirmLabel="Usuń"
      cancelLabel="Anuluj"
      onConfirm={() => {
        pending.onConfirm();
        setPending(null);
      }}
      onCancel={() => setPending(null)}
    />
  ) : null;

  return [dialog, (message, onConfirm) => setPending({ message, onConfirm })];
}
