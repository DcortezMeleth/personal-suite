import { useRef } from "react";

/**
 * Props for a modal backdrop that dismisses on a deliberate click outside the
 * dialog — and only then.
 *
 * Reacting to the backdrop's click event is the obvious approach and the wrong
 * one: a click is delivered to the nearest common ancestor of where the button
 * went down and where it came up, so dragging to select text in a field and
 * releasing past the panel's edge lands a click on the backdrop and reads as
 * "clicked outside". The dialog would shut mid-edit and lose what was typed.
 *
 * Requiring both press and release to be the backdrop itself keeps a genuine
 * click-outside working while a selection that merely ends there does nothing.
 * It also means the panel no longer needs to stop propagation to defend itself.
 */
export function useBackdropDismiss(onDismiss: () => void) {
  const pressedOnBackdrop = useRef(false);

  return {
    onMouseDown: (e: React.MouseEvent) => {
      pressedOnBackdrop.current = e.target === e.currentTarget;
    },
    onMouseUp: (e: React.MouseEvent) => {
      const pressed = pressedOnBackdrop.current;
      pressedOnBackdrop.current = false;
      if (pressed && e.target === e.currentTarget) onDismiss();
    },
  };
}
