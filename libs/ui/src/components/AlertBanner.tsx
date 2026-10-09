export type AlertLevel = "info" | "warning" | "danger" | "success";

export interface AlertBannerProps {
  level: AlertLevel;
  message: string;
  onDismiss?: () => void;
}

const styles: Record<AlertLevel, string> = {
  info: "bg-primary-50 border-primary-500 text-primary-700",
  warning: "bg-warning-50 border-warning-500 text-warning-600",
  danger: "bg-danger-50 border-danger-500 text-danger-600",
  success: "bg-success-50 border-success-500 text-success-600",
};

const icons: Record<AlertLevel, string> = {
  info: "ℹ",
  warning: "⚠",
  danger: "✕",
  success: "✓",
};

export function AlertBanner({ level, message, onDismiss }: AlertBannerProps) {
  return (
    <div className={`flex items-start gap-3 rounded-lg border-l-4 px-4 py-3 ${styles[level]}`}>
      <span className="mt-0.5 font-bold">{icons[level]}</span>
      <p className="flex-1 text-sm">{message}</p>
      {onDismiss && (
        <button
          onClick={onDismiss}
          className="ml-auto text-sm opacity-60 hover:opacity-100"
          aria-label="Dismiss"
        >
          ✕
        </button>
      )}
    </div>
  );
}
