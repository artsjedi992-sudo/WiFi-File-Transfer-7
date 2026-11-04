using System;
using System.IO;
using System.Net;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace WifiFileTransfer7
{
    public sealed class MainForm : Form
    {
        private readonly TextBox _phoneIp = new TextBox();
        private readonly TextBox _folder = new TextBox();
        private readonly Button _start = new Button();
        private readonly Button _send = new Button();
        private readonly Label _status = new Label();
        private readonly ProgressBar _progress = new ProgressBar();
        private HttpListener _listener;
        private CancellationTokenSource _cancel;

        public MainForm()
        {
            Text = "WiFi File Transfer 7";
            Width = 680; Height = 390; MinimumSize = new System.Drawing.Size(600, 350);
            StartPosition = FormStartPosition.CenterScreen;
            Font = new System.Drawing.Font("Segoe UI", 9F);
            BuildUi();
            FormClosing += (s, e) => StopServer();
        }

        private void BuildUi()
        {
            var title = new Label { Text = "WiFi File Transfer 7", Left = 22, Top = 18, Width = 600, Height = 35, Font = new System.Drawing.Font("Segoe UI", 18F, System.Drawing.FontStyle.Bold) };
            Controls.Add(title);
            Controls.Add(new Label { Text = "Папка за получените файлове:", Left = 24, Top = 82, Width = 220 });
            _folder.SetBounds(24, 108, 500, 27); _folder.Text = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments), "WiFi File Transfer");
            Controls.Add(_folder);
            var browse = new Button { Text = "Избери…", Left = 535, Top = 106, Width = 105, Height = 30 };
            browse.Click += (s, e) => { using (var d = new FolderBrowserDialog()) if (d.ShowDialog() == DialogResult.OK) _folder.Text = d.SelectedPath; };
            Controls.Add(browse);
            _start.Text = "Стартирай приемането"; _start.SetBounds(24, 151, 190, 36); _start.Click += async (s, e) => await StartServer();
            Controls.Add(_start);
            Controls.Add(new Label { Text = "IP адрес на телефона:", Left = 24, Top = 214, Width = 200 });
            _phoneIp.SetBounds(24, 240, 230, 28); _phoneIp.Text = "192.168.1.100"; Controls.Add(_phoneIp);
            _send.Text = "Изпрати файл към телефона"; _send.SetBounds(270, 237, 230, 34); _send.Click += async (s, e) => await SendFile();
            Controls.Add(_send);
            _progress.SetBounds(24, 292, 615, 20); Controls.Add(_progress);
            _status.SetBounds(24, 322, 615, 25); _status.Text = "Готово. Стартирайте приемането."; Controls.Add(_status);
        }

        private async Task StartServer()
        {
            if (_listener != null && _listener.IsListening) { StopServer(); return; }
            try
            {
                Directory.CreateDirectory(_folder.Text);
                _listener = new HttpListener();
                _listener.Prefixes.Add("http://+:8765/");
                _listener.Start();
                _cancel = new CancellationTokenSource();
                _start.Text = "Спри приемането";
                _status.Text = "Приемане активно на порт 8765. IP на компютъра се вижда с ipconfig.";
                await Task.Run(() => ListenLoop(_cancel.Token));
            }
            catch (Exception ex) { _status.Text = "Грешка: " + ex.Message; StopServer(); }
        }

        private void ListenLoop(CancellationToken token)
        {
            while (!token.IsCancellationRequested && _listener != null && _listener.IsListening)
            {
                try
                {
                    var context = _listener.GetContext();
                    Task.Run(() => HandleUpload(context));
                }
                catch (HttpListenerException) { break; }
                catch (ObjectDisposedException) { break; }
            }
        }

        private void HandleUpload(HttpListenerContext context)
        {
            try
            {
                if (context.Request.HttpMethod != "POST" || context.Request.Url.AbsolutePath != "/upload")
                { context.Response.StatusCode = 404; context.Response.Close(); return; }
                var name = Path.GetFileName(Uri.UnescapeDataString(context.Request.Headers["X-File-Name"] ?? "received_file.bin"));
                if (String.IsNullOrWhiteSpace(name)) name = "received_file.bin";
                var target = UniquePath(_folder.Text, name);
                using (var output = new FileStream(target, FileMode.CreateNew, FileAccess.Write))
                    context.Request.InputStream.CopyTo(output);
                var bytes = Encoding.UTF8.GetBytes("OK");
                context.Response.StatusCode = 200; context.Response.OutputStream.Write(bytes, 0, bytes.Length); context.Response.Close();
                BeginInvoke((Action)(() => _status.Text = "Получен файл: " + Path.GetFileName(target)));
            }
            catch (Exception ex)
            {
                try { context.Response.StatusCode = 500; context.Response.Close(); } catch { }
                try { BeginInvoke((Action)(() => _status.Text = "Грешка при приемане: " + ex.Message)); } catch { }
            }
        }

        private static string UniquePath(string folder, string name)
        {
            var path = Path.Combine(folder, name);
            if (!File.Exists(path)) return path;
            var baseName = Path.GetFileNameWithoutExtension(name);
            var ext = Path.GetExtension(name);
            int index = 1;
            do { path = Path.Combine(folder, baseName + " (" + index++ + ")" + ext); } while (File.Exists(path));
            return path;
        }

        private async Task SendFile()
        {
            using (var d = new OpenFileDialog())
            {
                if (d.ShowDialog() != DialogResult.OK) return;
                try
                {
                    _send.Enabled = false; _progress.Value = 0; _status.Text = "Изпращане…";
                    var info = new FileInfo(d.FileName);
                    var request = (HttpWebRequest)WebRequest.Create("http://" + _phoneIp.Text.Trim() + ":8766/upload");
                    request.Method = "POST";
                    request.ContentType = "application/octet-stream";
                    request.ContentLength = info.Length;
                    request.Timeout = 60000;
                    request.ReadWriteTimeout = 60000;
                    request.Headers["X-File-Name"] = Uri.EscapeDataString(info.Name);
                    using (var input = File.OpenRead(d.FileName))
                    using (var output = await request.GetRequestStreamAsync())
                    {
                        var buffer = new byte[64 * 1024];
                        int read;
                        long sent = 0;
                        while ((read = await input.ReadAsync(buffer, 0, buffer.Length)) > 0)
                        {
                            await output.WriteAsync(buffer, 0, read);
                            sent += read;
                            var percent = info.Length == 0 ? 100 : (int)(sent * 100 / info.Length);
                            if (!IsDisposed) BeginInvoke((Action)(() => _progress.Value = Math.Max(0, Math.Min(100, percent))));
                        }
                    }
                    using (var response = (HttpWebResponse)await request.GetResponseAsync())
                    {
                        if (response.StatusCode != HttpStatusCode.OK) throw new Exception("Телефонът върна HTTP " + (int)response.StatusCode);
                    }
                    _status.Text = "Файлът е изпратен успешно.";
                }
                catch (WebException ex)
                {
                    var detail = ex.Message;
                    try
                    {
                        using (var response = ex.Response as HttpWebResponse)
                        using (var stream = response == null ? null : response.GetResponseStream())
                        using (var reader = stream == null ? null : new StreamReader(stream, Encoding.UTF8))
                        {
                            if (response != null)
                                detail = "HTTP " + (int)response.StatusCode + " " + response.StatusDescription;
                            if (reader != null)
                            {
                                var serverMessage = reader.ReadToEnd();
                                if (!String.IsNullOrWhiteSpace(serverMessage)) detail += ": " + serverMessage;
                            }
                        }
                    }
                    catch { }
                    _status.Text = "Неуспешно изпращане: " + detail;
                }
                catch (Exception ex) { _status.Text = "Неуспешно изпращане: " + ex.Message; }
                finally { _send.Enabled = true; }
            }
        }

        private void StopServer()
        {
            try { if (_cancel != null) _cancel.Cancel(); if (_listener != null) { _listener.Stop(); _listener.Close(); } }
            catch { }
            _listener = null; _start.Text = "Стартирай приемането";
        }
    }
}
