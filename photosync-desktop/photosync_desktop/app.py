"""Tkinter UI for the portable PhotoSync desktop uploader."""

from __future__ import annotations

import os
import queue
import threading
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, messagebox, ttk
from tkinter.scrolledtext import ScrolledText

from .core import human_size, scan_media, validate_azure_settings
from .state import StateStore
from .uploader import AzureUploader, UploadConfiguration, UploadEvent, redact_error

APP_ROOT = Path(__file__).resolve().parent.parent
DATABASE_PATH = APP_ROOT / "data" / "photosync.sqlite3"


class PhotoSyncDesktopApp:
    def __init__(self, root: tk.Tk) -> None:
        self.root = root
        self.root.title("PhotoSync Desktop Uploader")
        self.root.geometry("860x680")
        self.root.minsize(720, 590)

        with StateStore(DATABASE_PATH) as state:
            self.device_id = state.device_id()
            completed_count = state.completed_count()

        self.account_url = tk.StringVar(
            value=os.environ.get("AZURE_STORAGE_ACCOUNT_URL", "")
        )
        self.container = tk.StringVar(value="photo-sync")
        self.sas_token = tk.StringVar(
            value=os.environ.get("AZURE_STORAGE_SAS_TOKEN", "")
        )
        self.source_root = tk.StringVar()
        self.status = tk.StringVar(
            value=f"Ready — {completed_count} upload(s) recorded for this desktop device."
        )
        self.progress_text = tk.StringVar(value="0 / 0")
        self.events: queue.SimpleQueue[UploadEvent] = queue.SimpleQueue()
        self.cancel_event = threading.Event()
        self.worker: threading.Thread | None = None

        self._build_ui()
        self.root.after(100, self._drain_events)

    def _build_ui(self) -> None:
        outer = ttk.Frame(self.root, padding=16)
        outer.pack(fill=tk.BOTH, expand=True)
        outer.columnconfigure(1, weight=1)
        outer.rowconfigure(8, weight=1)

        ttk.Label(outer, text="PhotoSync Desktop", font=("Segoe UI", 16, "bold")).grid(
            row=0, column=0, columnspan=3, sticky=tk.W, pady=(0, 4)
        )
        ttk.Label(
            outer,
            text="Recursively upload photos and videos as an independent PhotoSync device.",
        ).grid(row=1, column=0, columnspan=3, sticky=tk.W, pady=(0, 14))

        self._field(outer, 2, "Account URL", self.account_url)
        self._field(outer, 3, "Container", self.container)
        self._field(outer, 4, "SAS token", self.sas_token, secret=True)

        ttk.Label(outer, text="Source folder").grid(
            row=5, column=0, sticky=tk.W, padx=(0, 10), pady=5
        )
        ttk.Entry(outer, textvariable=self.source_root).grid(
            row=5, column=1, sticky=tk.EW, pady=5
        )
        ttk.Button(outer, text="Browse…", command=self._browse).grid(
            row=5, column=2, padx=(8, 0), pady=5
        )

        ttk.Label(outer, text="Desktop device ID").grid(
            row=6, column=0, sticky=tk.W, padx=(0, 10), pady=5
        )
        device_entry = ttk.Entry(outer)
        device_entry.insert(0, self.device_id)
        device_entry.configure(state="readonly")
        device_entry.grid(row=6, column=1, sticky=tk.EW, pady=5)
        ttk.Label(outer, text="Stored only in this app's data folder").grid(
            row=6, column=2, sticky=tk.W, padx=(8, 0), pady=5
        )

        buttons = ttk.Frame(outer)
        buttons.grid(row=7, column=0, columnspan=3, sticky=tk.EW, pady=(12, 10))
        self.scan_button = ttk.Button(
            buttons, text="Scan only", command=self._start_scan
        )
        self.scan_button.pack(side=tk.LEFT)
        self.upload_button = ttk.Button(
            buttons, text="Upload", command=self._start_upload
        )
        self.upload_button.pack(side=tk.LEFT, padx=8)
        self.cancel_button = ttk.Button(
            buttons, text="Cancel", command=self._cancel, state=tk.DISABLED
        )
        self.cancel_button.pack(side=tk.LEFT)
        ttk.Label(buttons, textvariable=self.progress_text).pack(side=tk.RIGHT)

        log_frame = ttk.LabelFrame(outer, text="Activity", padding=8)
        log_frame.grid(row=8, column=0, columnspan=3, sticky=tk.NSEW)
        log_frame.columnconfigure(0, weight=1)
        log_frame.rowconfigure(0, weight=1)
        self.log = ScrolledText(log_frame, height=14, wrap=tk.WORD, state=tk.DISABLED)
        self.log.grid(row=0, column=0, sticky=tk.NSEW)

        self.progress = ttk.Progressbar(outer, mode="determinate", maximum=1)
        self.progress.grid(row=9, column=0, columnspan=3, sticky=tk.EW, pady=(10, 4))
        ttk.Label(outer, textvariable=self.status).grid(
            row=10, column=0, columnspan=3, sticky=tk.W
        )

    @staticmethod
    def _field(
        parent: ttk.Frame,
        row: int,
        label: str,
        variable: tk.StringVar,
        secret: bool = False,
    ) -> None:
        ttk.Label(parent, text=label).grid(
            row=row, column=0, sticky=tk.W, padx=(0, 10), pady=5
        )
        ttk.Entry(parent, textvariable=variable, show="•" if secret else "").grid(
            row=row, column=1, columnspan=2, sticky=tk.EW, pady=5
        )

    def _browse(self) -> None:
        selected = filedialog.askdirectory(
            title="Select the top-level photo/video folder"
        )
        if selected:
            self.source_root.set(selected)

    def _source_path(self) -> Path | None:
        value = self.source_root.get().strip()
        if not value:
            messagebox.showerror(
                "Source folder required", "Select a folder to scan recursively."
            )
            return None
        path = Path(value)
        if not path.is_dir():
            messagebox.showerror(
                "Invalid source folder", "The selected source folder is not available."
            )
            return None
        return path

    def _start_scan(self) -> None:
        source = self._source_path()
        if source is None:
            return
        self.cancel_event.clear()
        self._reset_progress()
        self._set_running(True)
        self.status.set("Scanning every subfolder…")
        self._append_log(f"Scanning recursively: {source}")

        def scan() -> None:
            errors: list[str] = []
            try:
                files = []
                for media in scan_media(source, errors.append):
                    if self.cancel_event.is_set():
                        break
                    files.append(media)
                total_bytes = sum(media.size for media in files)
                for warning in errors:
                    self.events.put(UploadEvent("warning", warning))
                prefix = (
                    "Scan cancelled" if self.cancel_event.is_set() else "Scan complete"
                )
                self.events.put(
                    UploadEvent(
                        "scan_only_done",
                        f"{prefix}: {len(files)} photo/video file(s), {human_size(total_bytes)}.",
                        len(files),
                        len(files),
                    )
                )
            except Exception as error:  # noqa: BLE001 - report worker failure in the UI.
                self.events.put(UploadEvent("fatal", f"Scan failed: {error}"))

        self.worker = threading.Thread(target=scan, name="photosync-scan", daemon=True)
        self.worker.start()

    def _start_upload(self) -> None:
        source = self._source_path()
        if source is None:
            return
        try:
            account_url, container, sas_token = validate_azure_settings(
                self.account_url.get(), self.container.get(), self.sas_token.get()
            )
        except ValueError as error:
            messagebox.showerror("Azure configuration", str(error))
            return

        confirmed = messagebox.askyesno(
            "Start upload?",
            "The app will read the selected directory tree and add photos/videos to "
            f"{account_url}/{container}. Local files will not be changed.",
        )
        if not confirmed:
            return

        configuration = UploadConfiguration(
            account_url=account_url,
            container=container,
            sas_token=sas_token,
            source_root=source,
            database_path=DATABASE_PATH,
        )
        self.sas_token.set("")
        self.cancel_event.clear()
        self._reset_progress()
        self._set_running(True)
        self.status.set("Scanning every subfolder before upload…")
        self._append_log(f"Starting recursive upload from: {source}")
        uploader = AzureUploader(configuration, self.events.put, self.cancel_event)

        def upload() -> None:
            try:
                uploader.run()
            except Exception as error:  # noqa: BLE001 - report worker failure in the UI.
                self.events.put(
                    UploadEvent(
                        "fatal",
                        f"Upload stopped: {redact_error(error, configuration.sas_token)}",
                    )
                )

        self.worker = threading.Thread(
            target=upload, name="photosync-upload", daemon=True
        )
        self.worker.start()

    def _cancel(self) -> None:
        self.cancel_event.set()
        self.status.set("Cancellation requested; the current file may finish first…")

    def _set_running(self, running: bool) -> None:
        state = tk.DISABLED if running else tk.NORMAL
        self.scan_button.configure(state=state)
        self.upload_button.configure(state=state)
        self.cancel_button.configure(state=tk.NORMAL if running else tk.DISABLED)

    def _reset_progress(self) -> None:
        self.progress.configure(maximum=1, value=0)
        self.progress_text.set("0 / 0")

    def _append_log(self, message: str) -> None:
        self.log.configure(state=tk.NORMAL)
        self.log.insert(tk.END, message + "\n")
        self.log.see(tk.END)
        self.log.configure(state=tk.DISABLED)

    def _drain_events(self) -> None:
        try:
            while True:
                event = self.events.get_nowait()
                if event.total:
                    self.progress.configure(
                        maximum=max(event.total, 1), value=event.current
                    )
                    self.progress_text.set(f"{event.current} / {event.total}")
                if event.kind == "progress" and event.bytes_total:
                    self.status.set(
                        f"{event.message}: {human_size(event.bytes_current)} / "
                        f"{human_size(event.bytes_total)}"
                    )
                elif event.kind in {"file_start", "scan_complete"}:
                    self.status.set(event.message)
                    self._append_log(event.message)
                elif event.kind in {
                    "uploaded",
                    "recovered",
                    "skipped",
                    "warning",
                    "error",
                }:
                    self._append_log(event.message)
                elif event.kind in {"done", "scan_only_done", "fatal"}:
                    self.status.set(event.message)
                    self._append_log(event.message)
                    self._set_running(False)
                    if event.kind == "fatal":
                        messagebox.showerror("PhotoSync", event.message)
        except queue.Empty:
            pass
        self.root.after(100, self._drain_events)


def main() -> None:
    root = tk.Tk()
    PhotoSyncDesktopApp(root)
    root.mainloop()
