#!/usr/bin/env python3
"""
Flip to Read — Windows Extension Setup Installer Builder
Packages extension/dist into a self-contained, standalone Windows executable:
FlipToRead-Extension-Setup.exe

Requirements:
- Python 3.8+ (for building only; end-users do NOT need Python)
- Windows .NET Framework 4.5+ csc.exe (pre-installed on all Windows 10/11 systems)
"""

import os
import sys
import json
import zipfile
import tempfile
import subprocess
from pathlib import Path

try:
    from PIL import Image
    HAS_PIL = True
except ImportError:
    HAS_PIL = False


CSC_PATHS = [
    r"C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe",
    r"C:\Windows\Microsoft.NET\Framework\v4.0.30319\csc.exe",
]


def find_csc() -> str:
    for p in CSC_PATHS:
        if os.path.isfile(p):
            return p
    # Fallback to PATH
    import shutil
    csc_in_path = shutil.which("csc.exe")
    if csc_in_path:
        return csc_in_path
    raise FileNotFoundError("Microsoft C# compiler (csc.exe) not found on this Windows system.")


def create_ico_from_png(png_path: str, ico_path: str):
    if HAS_PIL and os.path.isfile(png_path):
        try:
            img = Image.open(png_path)
            img.save(ico_path, format="ICO", sizes=[(16, 16), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
            return True
        except Exception as e:
            print(f"[Warning] Failed to generate .ico from {png_path}: {e}", file=sys.stderr)
    return False


def get_csharp_source(version: str) -> str:
    return f"""using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Threading;
using System.Windows.Forms;

[assembly: AssemblyTitle("Flip to Read Extension Setup")]
[assembly: AssemblyDescription("Desktop setup utility for Flip to Read Chrome/Edge Extension")]
[assembly: AssemblyConfiguration("")]
[assembly: AssemblyCompany("Flip to Read")]
[assembly: AssemblyProduct("Flip to Read")]
[assembly: AssemblyCopyright("Copyright © 2026 Nishant Trivedi")]
[assembly: AssemblyTrademark("")]
[assembly: AssemblyCulture("")]
[assembly: AssemblyVersion("{version}.0")]
[assembly: AssemblyFileVersion("{version}.0")]

namespace FlipToRead.Setup
{{
    static class Program
    {{
        [STAThread]
        static void Main(string[] args)
        {{
            bool isSilent = args != null && args.Length > 0 &&
                (args[0].Equals("/silent", StringComparison.OrdinalIgnoreCase) ||
                 args[0].Equals("--silent", StringComparison.OrdinalIgnoreCase) ||
                 args[0].Equals("--test", StringComparison.OrdinalIgnoreCase) ||
                 args[0].Equals("/test", StringComparison.OrdinalIgnoreCase));

            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);

            string targetDir = "";
            bool extractSuccess = false;
            string errorMessage = "";

            try
            {{
                string localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
                string baseDir = Path.Combine(localAppData, "FlipToRead");
                targetDir = Path.Combine(baseDir, "extension");

                // Clean existing installation if present
                if (Directory.Exists(targetDir))
                {{
                    try
                    {{
                        Directory.Delete(targetDir, true);
                    }}
                    catch
                    {{
                        // If directory is locked, delete contents as best as possible
                        foreach (string file in Directory.GetFiles(targetDir, "*.*", SearchOption.AllDirectories))
                        {{
                            try {{ File.Delete(file); }} catch {{}}
                        }}
                    }}
                }}

                Directory.CreateDirectory(targetDir);

                // Extract bundled extension.zip from embedded resource
                Assembly asm = Assembly.GetExecutingAssembly();
                using (Stream zipStream = asm.GetManifestResourceStream("extension.zip"))
                {{
                    if (zipStream == null)
                    {{
                        throw new InvalidOperationException("Embedded extension resource not found in installer.");
                    }}

                    using (ZipArchive archive = new ZipArchive(zipStream))
                    {{
                        foreach (ZipArchiveEntry entry in archive.Entries)
                        {{
                            string fullPath = Path.GetFullPath(Path.Combine(targetDir, entry.FullName));
                            // Security check: Prevent Zip-Slip / path traversal
                            if (!fullPath.StartsWith(targetDir, StringComparison.OrdinalIgnoreCase))
                            {{
                                throw new InvalidOperationException("Unsafe archive entry path detected: " + entry.FullName);
                            }}

                            if (string.IsNullOrEmpty(entry.Name))
                            {{
                                Directory.CreateDirectory(fullPath);
                            }}
                            else
                            {{
                                Directory.CreateDirectory(Path.GetDirectoryName(fullPath));
                                entry.ExtractToFile(fullPath, true);
                            }}
                        }}
                    }}
                }}

                string manifestCheck = Path.Combine(targetDir, "manifest.json");
                if (!File.Exists(manifestCheck))
                {{
                    throw new FileNotFoundException("Extraction finished but manifest.json was not found at destination root.");
                }}

                extractSuccess = true;
            }}
            catch (Exception ex)
            {{
                errorMessage = ex.Message;
            }}

            if (!extractSuccess)
            {{
                MessageBox.Show(
                    "Setup could not prepare the Flip to Read extension.\\n\\nError: " + errorMessage,
                    "Flip to Read Extension Setup Error",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Error
                );
                return;
            }}

            // Copy extension directory path to Windows Clipboard
            try
            {{
                Clipboard.SetText(targetDir);
            }}
            catch
            {{
                // In case of clipboard lock, retry once
                try
                {{
                    Thread.Sleep(100);
                    Clipboard.SetText(targetDir);
                }}
                catch {{}}
            }}

            if (isSilent)
            {{
                return;
            }}

            // Attempt to detect Google Chrome or Edge and open chrome://extensions
            OpenExtensionsPage();

            // Display completion Form
            Application.Run(new SetupCompleteForm(targetDir));
        }}

        public static void OpenExtensionsPage()
        {{
            string chromePath = FindChromePath();
            if (!string.IsNullOrEmpty(chromePath) && File.Exists(chromePath))
            {{
                try
                {{
                    Process.Start(new ProcessStartInfo(chromePath, "chrome://extensions") {{ UseShellExecute = true }});
                    return;
                }}
                catch {{}}
            }}

            string edgePath = FindEdgePath();
            if (!string.IsNullOrEmpty(edgePath) && File.Exists(edgePath))
            {{
                try
                {{
                    Process.Start(new ProcessStartInfo(edgePath, "edge://extensions") {{ UseShellExecute = true }});
                    return;
                }}
                catch {{}}
            }}

            // Fallback: system command
            try
            {{
                Process.Start(new ProcessStartInfo("cmd.exe", "/c start chrome://extensions") {{ CreateNoWindow = true, UseShellExecute = false }});
            }}
            catch {{}}
        }}

        static string FindChromePath()
        {{
            string[] candidates = new string[]
            {{
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), @"Google\\Chrome\\Application\\chrome.exe"),
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86), @"Google\\Chrome\\Application\\chrome.exe"),
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), @"Google\\Chrome\\Application\\chrome.exe")
            }};
            foreach (string p in candidates)
            {{
                if (File.Exists(p)) return p;
            }}
            return "";
        }}

        static string FindEdgePath()
        {{
            string[] candidates = new string[]
            {{
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86), @"Microsoft\\Edge\\Application\\msedge.exe"),
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), @"Microsoft\\Edge\\Application\\msedge.exe")
            }};
            foreach (string p in candidates)
            {{
                if (File.Exists(p)) return p;
            }}
            return "";
        }}
    }}

    public class SetupCompleteForm : Form
    {{
        private readonly string extensionPath;

        public SetupCompleteForm(string path)
        {{
            this.extensionPath = path;
            InitializeComponent();
        }}

        private void InitializeComponent()
        {{
            this.Text = "Flip to Read — Extension Setup";
            this.Size = new Size(540, 420);
            this.StartPosition = FormStartPosition.CenterScreen;
            this.FormBorderStyle = FormBorderStyle.FixedDialog;
            this.MaximizeBox = false;
            this.MinimizeBox = false;
            this.BackColor = Color.FromArgb(250, 252, 251);
            this.Font = new Font("Segoe UI", 9F, FontStyle.Regular, GraphicsUnit.Point);

            // Header Panel
            Panel header = new Panel
            {{
                Dock = DockStyle.Top,
                Height = 70,
                BackColor = Color.FromArgb(38, 70, 83)
            }};

            Label titleLabel = new Label
            {{
                Text = "Flip to Read Extension is Ready!",
                Font = new Font("Segoe UI", 12F, FontStyle.Bold),
                ForeColor = Color.White,
                Location = new Point(20, 14),
                AutoSize = true
            }};

            Label subtitleLabel = new Label
            {{
                Text = "Files extracted & path automatically copied to your clipboard.",
                Font = new Font("Segoe UI", 8.8F),
                ForeColor = Color.FromArgb(230, 240, 245),
                Location = new Point(21, 40),
                AutoSize = true
            }};

            header.Controls.Add(titleLabel);
            header.Controls.Add(subtitleLabel);
            this.Controls.Add(header);

            // Content Panel
            Panel content = new Panel
            {{
                Location = new Point(20, 85),
                Size = new Size(485, 230)
            }};

            Label stepHeader = new Label
            {{
                Text = "Next: Complete 3 quick steps in the opened Chrome window:",
                Font = new Font("Segoe UI", 9.5F, FontStyle.Bold),
                ForeColor = Color.FromArgb(40, 40, 40),
                Location = new Point(0, 0),
                AutoSize = true
            }};
            content.Controls.Add(stepHeader);

            string[] steps = new string[]
            {{
                "1. Turn ON \\"Developer mode\\" (toggle switch in the top-right corner).",
                "2. Click the \\"Load unpacked\\" button (in the top-left toolbar).",
                "3. Press Ctrl + V in the folder dialog and click \\"Select Folder\\"."
            }};

            int y = 30;
            foreach (string step in steps)
            {{
                Label stepLbl = new Label
                {{
                    Text = step,
                    Font = new Font("Segoe UI", 9F, FontStyle.Regular),
                    ForeColor = Color.FromArgb(50, 50, 50),
                    Location = new Point(10, y),
                    Size = new Size(465, 22)
                }};
                content.Controls.Add(stepLbl);
                y += 26;
            }}

            // Path box container
            Label pathTitle = new Label
            {{
                Text = "Extension Folder Path (already in your clipboard):",
                Font = new Font("Segoe UI", 8.2F, FontStyle.Bold),
                ForeColor = Color.FromArgb(100, 110, 120),
                Location = new Point(0, y + 10),
                AutoSize = true
            }};
            content.Controls.Add(pathTitle);

            TextBox txtPath = new TextBox
            {{
                Text = extensionPath,
                ReadOnly = true,
                BackColor = Color.FromArgb(240, 244, 246),
                ForeColor = Color.FromArgb(40, 60, 70),
                Location = new Point(2, y + 32),
                Size = new Size(385, 24)
            }};
            content.Controls.Add(txtPath);

            Button btnCopy = new Button
            {{
                Text = "Copy Path",
                Location = new Point(395, y + 30),
                Size = new Size(88, 27),
                BackColor = Color.FromArgb(42, 157, 143),
                ForeColor = Color.White,
                FlatStyle = FlatStyle.Flat,
                Font = new Font("Segoe UI", 8.5F, FontStyle.Bold),
                Cursor = Cursors.Hand
            }};
            btnCopy.FlatAppearance.BorderSize = 0;
            btnCopy.Click += (s, e) =>
            {{
                try
                {{
                    Clipboard.SetText(extensionPath);
                    MessageBox.Show("Folder path copied to clipboard!", "Copied", MessageBoxButtons.OK, MessageBoxIcon.Information);
                }}
                catch {{}}
            }};
            content.Controls.Add(btnCopy);

            this.Controls.Add(content);

            // Bottom Action Bar
            Panel footer = new Panel
            {{
                Dock = DockStyle.Bottom,
                Height = 60,
                BackColor = Color.FromArgb(240, 244, 246)
            }};

            Button btnOpenChrome = new Button
            {{
                Text = "Re-open Chrome Extensions",
                Location = new Point(20, 15),
                Size = new Size(185, 32),
                BackColor = Color.White,
                ForeColor = Color.FromArgb(38, 70, 83),
                FlatStyle = FlatStyle.Flat,
                Font = new Font("Segoe UI", 8.5F, FontStyle.Regular),
                Cursor = Cursors.Hand
            }};
            btnOpenChrome.FlatAppearance.BorderColor = Color.FromArgb(200, 210, 215);
            btnOpenChrome.Click += (s, e) =>
            {{
                Program.OpenExtensionsPage();
            }};
            footer.Controls.Add(btnOpenChrome);

            Button btnOpenFolder = new Button
            {{
                Text = "Open Folder",
                Location = new Point(215, 15),
                Size = new Size(100, 32),
                BackColor = Color.White,
                ForeColor = Color.FromArgb(38, 70, 83),
                FlatStyle = FlatStyle.Flat,
                Font = new Font("Segoe UI", 8.5F, FontStyle.Regular),
                Cursor = Cursors.Hand
            }};
            btnOpenFolder.FlatAppearance.BorderColor = Color.FromArgb(200, 210, 215);
            btnOpenFolder.Click += (s, e) =>
            {{
                try {{ Process.Start("explorer.exe", extensionPath); }} catch {{}}
            }};
            footer.Controls.Add(btnOpenFolder);

            Button btnDone = new Button
            {{
                Text = "Done",
                Location = new Point(415, 15),
                Size = new Size(90, 32),
                BackColor = Color.FromArgb(38, 70, 83),
                ForeColor = Color.White,
                FlatStyle = FlatStyle.Flat,
                Font = new Font("Segoe UI", 9F, FontStyle.Bold),
                Cursor = Cursors.Hand
            }};
            btnDone.FlatAppearance.BorderSize = 0;
            btnDone.Click += (s, e) => this.Close();
            footer.Controls.Add(btnDone);

            this.Controls.Add(footer);
        }}
    }}
}}
"""


def build():
    script_dir = Path(__file__).resolve().parent
    repo_root = script_dir.parent
    dist_dir = repo_root / "extension" / "dist"
    static_dir = repo_root / "java-app" / "src" / "main" / "resources" / "static"
    output_exe = static_dir / "FlipToRead-Extension-Setup.exe"

    print("==================================================")
    print("  Flip to Read — Windows Setup Builder")
    print("==================================================")

    # 1. Verify extension/dist exists and contains manifest.json
    manifest_path = dist_dir / "manifest.json"
    if not manifest_path.is_file():
        print("[-] extension/dist not found or incomplete. Building extension first...")
        ext_dir = repo_root / "extension"
        subprocess.run(["npm", "run", "build"], cwd=ext_dir, check=True)

    with open(manifest_path, "r", encoding="utf-8") as f:
        manifest = json.load(f)
    version = manifest.get("version", "1.0.0")
    print(f"[+] Extension Manifest V3 verified (Version {version})")

    # 2. Locate csc.exe compiler
    csc = find_csc()
    print(f"[+] Using C# Compiler: {csc}")

    with tempfile.TemporaryDirectory() as td:
        temp_dir = Path(td)
        zip_path = temp_dir / "extension.zip"
        cs_path = temp_dir / "Setup.cs"
        ico_path = temp_dir / "setup.ico"

        # 3. Create zip of extension/dist
        print(f"[+] Packaging extension files into archive...")
        file_count = 0
        with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
            for root, _, files in os.walk(dist_dir):
                for file in files:
                    full_p = Path(root) / file
                    rel_p = full_p.relative_to(dist_dir)
                    zf.write(full_p, str(rel_p).replace("\\", "/"))
                    file_count += 1
        print(f"[+] Bundled {file_count} extension files ({zip_path.stat().st_size:,} bytes)")

        # 4. Generate app icon if possible
        png_icon = static_dir / "logo-icon.png"
        has_icon = create_ico_from_png(str(png_icon), str(ico_path))

        # 5. Write C# source code
        with open(cs_path, "w", encoding="utf-8") as f:
            f.write(get_csharp_source(version))

        # 6. Ensure destination directory exists
        static_dir.mkdir(parents=True, exist_ok=True)

        # 7. Compile standalone executable with csc.exe
        print(f"[+] Compiling standalone executable: {output_exe.name}...")
        cmd = [
            csc,
            "/nologo",
            "/target:winexe",
            "/optimize+",
            "/platform:anycpu",
            f"/resource:{zip_path},extension.zip",
            "/r:System.dll,System.Drawing.dll,System.Windows.Forms.dll,System.IO.Compression.dll,System.IO.Compression.FileSystem.dll",
            f"/out:{output_exe}",
        ]
        if has_icon:
            cmd.append(f"/win32icon:{ico_path}")
        cmd.append(str(cs_path))

        result = subprocess.run(cmd, capture_output=True, text=True)
        if result.returncode != 0:
            print(f"[-] Compilation failed with code {result.returncode}:", file=sys.stderr)
            print(result.stdout, file=sys.stderr)
            print(result.stderr, file=sys.stderr)
            sys.exit(1)

    exe_size = output_exe.stat().st_size
    print("==================================================")
    print(f"  BUILD SUCCESSFUL!")
    print(f"  Executable: {output_exe}")
    print(f"  Size: {exe_size:,} bytes ({exe_size / 1024:.1f} KB)")
    print(f"  Static URL: /FlipToRead-Extension-Setup.exe")
    print("==================================================")


if __name__ == "__main__":
    build()
