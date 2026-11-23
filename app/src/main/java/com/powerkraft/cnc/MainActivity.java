package com.powerkraft.cnc;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ContentResolver;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Set;

public class MainActivity extends Activity {

    private static final int REQ_BT = 1;
    private static final int REQ_PICK = 2;
    private static final int REQ_SAVE = 3;
    private static final int BLUE = Color.rgb(11, 107, 203);
    private static final int GREEN = Color.rgb(26, 127, 75);
    private static final int RED = Color.rgb(190, 40, 40);
    private static final int GRAY = Color.rgb(90, 100, 110);

    private final BtLink link = new BtLink();
    private int screen = 0;               // 0 baglan, 1 dosyalar, 2 duzenleyici
    private boolean busy = false;

    // dosya ekrani
    private TextView status;
    private ProgressBar bar;
    private ListView listView;
    private ArrayAdapter<String> adapter;
    private final ArrayList<BtLink.Entry> files = new ArrayList<BtLink.Entry>();
    private final ArrayList<Button> actionButtons = new ArrayList<Button>();
    private String serial = "";

    // duzenleyici
    private EditText editor;
    private String editName;
    private boolean editCrLf;

    private String pendingSaveName;

    private interface Job { void run() throws Exception; }

    // ===============================================================
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        showConnect();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeLink();
    }

    private void closeLink() {
        if (!link.isOpen()) return;
        new Thread(new Runnable() {
            public void run() {
                link.exit();
                link.close();
            }
        }).start();
    }

    @Override
    public void onBackPressed() {
        if (busy) return;
        if (screen == 2) {
            askCloseEditor();
        } else if (screen == 1) {
            closeLink();
            showConnect();
        } else {
            super.onBackPressed();
        }
    }

    // ===============================================================
    // Yardimci arayuz
    // ===============================================================
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(16), dp(16), dp(16));
        l.setBackgroundColor(Color.WHITE);
        return l;
    }

    private TextView label(String t, int sp, boolean bold, int color) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private Button button(String t, int color, View.OnClickListener c) {
        Button b = new Button(this);
        b.setText(t);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(color);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setOnClickListener(c);
        return b;
    }

    private LinearLayout.LayoutParams lp(int w, int h, float weight, int margin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight);
        p.setMargins(margin, margin, margin, margin);
        return p;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    // ===============================================================
    // 1) Baglanti ekrani
    // ===============================================================
    private void showConnect() {
        screen = 0;
        busy = false;
        LinearLayout l = column();
        l.addView(label("DNC_SERVER_VER5", 24, true, Color.BLACK));
        TextView help = label("Cihazda Gönder/Al menüsünden PC BAGLANTI seçin. HC-06 modülü telefonun Bluetooth ayarlarında önceden eşleştirilmiş olmalıdır (şifre 1234).", 15, false, GRAY);
        help.setPadding(0, dp(8), 0, dp(16));
        l.addView(help);

        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            l.addView(label("Bluetooth izni bekleniyor...", 16, false, GRAY));
            setContentView(l);
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT);
            return;
        }

        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null) {
            l.addView(label("Bu telefonda Bluetooth yok.", 16, false, RED));
        } else if (!ad.isEnabled()) {
            l.addView(label("Bluetooth kapalı. Açıp bu ekrana tekrar gelin.", 16, false, RED));
            l.addView(button("Tekrar dene", BLUE, new View.OnClickListener() {
                public void onClick(View v) { showConnect(); }
            }), lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(52), 0, dp(4)));
        } else {
            Set<BluetoothDevice> bonded = ad.getBondedDevices();
            if (bonded == null || bonded.isEmpty()) {
                l.addView(label("Eşleşmiş cihaz yok. Telefonun Bluetooth ayarlarında HC-06'yı eşleştirin, sonra buraya dönün.", 16, false, RED));
            } else {
                l.addView(label("Cihazı seçin:", 16, true, Color.BLACK));
                for (final BluetoothDevice d : bonded) {
                    String name = d.getName() == null ? "(adsız)" : d.getName();
                    l.addView(button(name + "\n" + d.getAddress(), BLUE, new View.OnClickListener() {
                        public void onClick(View v) { connect(d); }
                    }), lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(64), 0, dp(4)));
                }
            }
            l.addView(button("Listeyi yenile", GRAY, new View.OnClickListener() {
                public void onClick(View v) { showConnect(); }
            }), lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(48), 0, dp(4)));
        }
        setContentView(l);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == REQ_BT) {
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) showConnect();
            else {
                LinearLayout l = column();
                l.addView(label("Bluetooth izni verilmedi. Uygulama izni olmadan çalışamaz.", 16, false, RED));
                l.addView(button("İzin iste", BLUE, new View.OnClickListener() {
                    public void onClick(View v) { showConnect(); }
                }));
                setContentView(l);
            }
        }
    }

    private void connect(final BluetoothDevice d) {
        final LinearLayout l = column();
        l.addView(label("Bağlanılıyor...", 20, true, Color.BLACK));
        setContentView(l);
        busy = true;
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                String sn = "";
                try {
                    BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
                    if (ad != null) ad.cancelDiscovery();
                    link.open(d);
                    sn = link.ping();
                } catch (Exception e) {
                    link.close();
                    err = e.getMessage();
                }
                final String ferr = err;
                final String fsn = sn;
                runOnUiThread(new Runnable() {
                    public void run() {
                        busy = false;
                        if (ferr != null) {
                            showConnect();
                            toast(ferr);
                        } else {
                            serial = fsn;
                            showFiles();
                            refresh();
                        }
                    }
                });
            }
        }).start();
    }

    // ===============================================================
    // 2) Dosya ekrani
    // ===============================================================
    private void showFiles() {
        screen = 1;
        actionButtons.clear();
        LinearLayout l = column();
        l.setPadding(dp(12), dp(12), dp(12), dp(12));

        l.addView(label("SD kart dosyaları", 20, true, Color.BLACK));
        status = label("Bağlandı. Cihaz: " + serial, 14, false, GREEN);
        status.setPadding(0, dp(4), 0, dp(4));
        l.addView(status);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        l.addView(bar, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(8), 0, 0));

        listView = new ListView(this);
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_single_choice, new ArrayList<String>());
        listView.setAdapter(adapter);
        listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) { }
        });
        l.addView(listView, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f, 0));

        l.addView(row(
                act("Aç / Düzenle", BLUE, new View.OnClickListener() { public void onClick(View v) { openEdit(); } }),
                act("Yenile", GRAY, new View.OnClickListener() { public void onClick(View v) { refresh(); } })));
        l.addView(row(
                act("Telefondan ekle", GREEN, new View.OnClickListener() { public void onClick(View v) { pickFile(); } }),
                act("Telefona kaydet", GREEN, new View.OnClickListener() { public void onClick(View v) { saveToPhone(); } })));
        LinearLayout last = new LinearLayout(this);
        last.addView(act("Sil", RED, new View.OnClickListener() { public void onClick(View v) { deleteSelected(); } }),
                lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(52), 1f, dp(3)));
        l.addView(last);
        setContentView(l);
    }

    private Button act(String t, int color, View.OnClickListener c) {
        Button b = button(t, color, c);
        actionButtons.add(b);
        return b;
    }

    private LinearLayout row(Button a, Button b) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.addView(a, lp(0, dp(52), 1f, dp(3)));
        r.addView(b, lp(0, dp(52), 1f, dp(3)));
        return r;
    }

    private void setStatus(String t, int color) {
        if (status != null) {
            status.setText(t);
            status.setTextColor(color);
        }
    }

    private void setProgress(final long done, final long total) {
        runOnUiThread(new Runnable() {
            public void run() {
                if (bar != null && total > 0) bar.setProgress((int) (done * 100 / total));
            }
        });
    }

    private void setBusy(boolean b) {
        busy = b;
        for (Button x : actionButtons) x.setEnabled(!b);
        if (!b && bar != null) bar.setProgress(0);
    }

    private void work(String msg, final Job job, final Runnable done) {
        if (busy) return;
        setBusy(true);
        setStatus(msg, GRAY);
        new Thread(new Runnable() {
            public void run() {
                Exception err = null;
                try {
                    job.run();
                } catch (Exception e) {
                    err = e;
                }
                final Exception fe = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        setBusy(false);
                        if (fe == null) {
                            if (done != null) done.run();
                        } else if (fe instanceof BtLink.DeviceEx) {
                            setStatus("Hata: " + fe.getMessage(), RED);
                            toast(fe.getMessage());
                        } else {
                            link.close();
                            showConnect();
                            toast("Bağlantı koptu: " + fe.getMessage());
                        }
                    }
                });
            }
        }).start();
    }

    private void refresh() {
        final ArrayList<BtLink.Entry> res = new ArrayList<BtLink.Entry>();
        work("Liste okunuyor...", new Job() {
            public void run() throws Exception {
                res.addAll(link.list());
            }
        }, new Runnable() {
            public void run() {
                files.clear();
                files.addAll(res);
                adapter.clear();
                for (BtLink.Entry e : files) adapter.add(e.name + "     " + sizeText(e.size));
                adapter.notifyDataSetChanged();
                listView.clearChoices();
                setStatus(files.size() + " dosya. Cihaz: " + serial, GREEN);
            }
        });
    }

    private static String sizeText(long n) {
        if (n < 1024) return n + " bayt";
        return String.format(java.util.Locale.US, "%.1f KB", n / 1024.0);
    }

    private BtLink.Entry selected() {
        int p = listView.getCheckedItemPosition();
        if (p < 0 || p >= files.size()) {
            toast("Önce listeden bir dosya seçin.");
            return null;
        }
        return files.get(p);
    }

    // ---------------- Sil ----------------
    private void deleteSelected() {
        final BtLink.Entry e = selected();
        if (e == null) return;
        new AlertDialog.Builder(this)
                .setTitle("Silinsin mi?")
                .setMessage(e.name + " SD karttan kalıcı olarak silinecek.")
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Sil", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        work("Siliniyor...", new Job() {
                            public void run() throws Exception { link.delete(e.name); }
                        }, new Runnable() {
                            public void run() { refresh(); }
                        });
                    }
                }).show();
    }

    // ---------------- Telefona kaydet ----------------
    private void saveToPhone() {
        BtLink.Entry e = selected();
        if (e == null) return;
        pendingSaveName = e.name;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/octet-stream");
        i.putExtra(Intent.EXTRA_TITLE, e.name);
        startActivityForResult(i, REQ_SAVE);
    }

    // ---------------- Karta gonder ----------------
    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();

        if (req == REQ_SAVE) {
            final String name = pendingSaveName;
            work("Dosya karttan okunuyor...", new Job() {
                public void run() throws Exception {
                    byte[] bytes = link.get(name, new BtLink.Progress() {
                        public void onProgress(long d, long t) { setProgress(d, t); }
                    });
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    if (os == null) throw new BtLink.DeviceEx("Dosya yazılamadı.");
                    try { os.write(bytes); } finally { os.close(); }
                }
            }, new Runnable() {
                public void run() {
                    setStatus("Telefona kaydedildi: " + name, GREEN);
                    toast("Kaydedildi: " + name);
                }
            });
        } else if (req == REQ_PICK) {
            final byte[] bytes;
            try {
                bytes = readAll(uri);
            } catch (Exception e) {
                toast("Dosya okunamadı: " + e.getMessage());
                return;
            }
            askName(displayName(uri), new NameCallback() {
                public void ok(final String name) {
                    showEditor(name, bytes, true);     // once goster/duzenle, sonra gonder
                }
            });
        }
    }

    private byte[] readAll(Uri uri) throws Exception {
        InputStream is = getContentResolver().openInputStream(uri);
        if (is == null) throw new Exception("açılamadı");
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try {
            byte[] b = new byte[8192];
            int n;
            while ((n = is.read(b)) > 0) bo.write(b, 0, n);
        } finally {
            is.close();
        }
        return bo.toByteArray();
    }

    private String displayName(Uri uri) {
        String name = "";
        Cursor c = null;
        try {
            ContentResolver cr = getContentResolver();
            c = cr.query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int ix = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (ix >= 0) name = c.getString(ix);
            }
        } catch (Exception ignore) {
        } finally {
            if (c != null) c.close();
        }
        return name == null ? "" : name;
    }

    /** Telefondaki dosya adindan 8.3 uyumlu bir oneri uretir. */
    private static String suggest(String fn) {
        String base = fn, ext = "";
        int dot = fn.lastIndexOf('.');
        if (dot > 0) {
            base = fn.substring(0, dot);
            ext = fn.substring(dot + 1);
        }
        base = clean(base);
        ext = clean(ext);
        if (base.length() > 8) base = base.substring(0, 8);
        if (ext.length() > 3) ext = ext.substring(0, 3);
        if (base.length() == 0) base = "PROGRAM";
        return ext.length() == 0 ? base : base + "." + ext;
    }

    private static String clean(String s) {
        StringBuilder sb = new StringBuilder();
        String t = s.replace('ı', 'I').replace('İ', 'I').replace('ş', 'S').replace('Ş', 'S')
                .replace('ğ', 'G').replace('Ğ', 'G').replace('ü', 'U').replace('Ü', 'U')
                .replace('ö', 'O').replace('Ö', 'O').replace('ç', 'C').replace('Ç', 'C').toUpperCase();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-') sb.append(c);
        }
        return sb.toString();
    }

    private interface NameCallback { void ok(String name); }

    private void askName(String phoneName, final NameCallback cb) {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(12)});
        et.setText(suggest(phoneName));
        et.setSelectAllOnFocus(true);
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Kartta kullanılacak ad")
                .setMessage("En fazla 8 karakter, nokta ve en fazla 3 karakter uzantı. Örnek: O0001.NC")
                .setView(et)
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Devam", null)
                .create();
        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            public void onShow(DialogInterface d) {
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        String n = BtLink.normalize(et.getText().toString());
                        if (n == null) {
                            et.setError("Geçersiz ad");
                            return;
                        }
                        dlg.dismiss();
                        cb.ok(n);
                    }
                });
            }
        });
        dlg.show();
    }

    // ===============================================================
    // 3) Duzenleyici
    // ===============================================================
    private void openEdit() {
        final BtLink.Entry e = selected();
        if (e == null) return;
        if (e.size > 200000) {
            toast("Bu dosya telefonda düzenlemek için çok büyük (200 KB üstü). Telefona kaydedin.");
            return;
        }
        final byte[][] holder = new byte[1][];
        work("Dosya karttan okunuyor...", new Job() {
            public void run() throws Exception {
                holder[0] = link.get(e.name, new BtLink.Progress() {
                    public void onProgress(long d, long t) { setProgress(d, t); }
                });
            }
        }, new Runnable() {
            public void run() { showEditor(e.name, holder[0]); }
        });
    }

    private void showEditor(String name, byte[] data) { showEditor(name, data, false); }

    private void showEditor(String name, byte[] data, boolean forSend) {
        screen = 2;
        editName = name;
        String text;
        try {
            text = new String(data, "ISO-8859-1");
        } catch (Exception ex) {
            text = "";
        }
        editCrLf = text.contains("\r\n");
        text = text.replace("\r\n", "\n").replace("\r", "\n");

        LinearLayout l = column();
        l.setPadding(dp(10), dp(10), dp(10), dp(10));
        l.addView(label(forSend ? name + "  (karta henüz gönderilmedi)" : name + "  (düzenleniyor)", 18, true, Color.BLACK));
        if (forSend) {
            TextView h = label("Gözden geçirip düzenleyin. 'Karta gönder'e basana kadar SD karta bir şey yazılmaz.", 13, false, GRAY);
            l.addView(h);
        }

        editor = new EditText(this);
        editor.setText(text);
        editor.setTypeface(Typeface.MONOSPACE);
        editor.setTextSize(14);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        editor.setHorizontallyScrolling(true);
        l.addView(editor, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f, 0));

        LinearLayout r = new LinearLayout(this);
        r.addView(button(forSend ? "Karta gönder" : "Karta kaydet", GREEN, new View.OnClickListener() {
            public void onClick(View v) { saveEditor(); }
        }), lp(0, dp(52), 1f, dp(3)));
        r.addView(button("Vazgeç", GRAY, new View.OnClickListener() {
            public void onClick(View v) { askCloseEditor(); }
        }), lp(0, dp(52), 1f, dp(3)));
        l.addView(r);
        setContentView(l);
    }

    private void askCloseEditor() {
        new AlertDialog.Builder(this)
                .setTitle("Çıkılsın mı?")
                .setMessage("Kaydedilmemiş değişiklikler kaybolur. Kartta hiçbir şey değişmez.")
                .setNegativeButton("Düzenlemeye devam", null)
                .setPositiveButton("Çık", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        showFiles();
                        refresh();
                    }
                }).show();
    }

    private void saveEditor() {
        String t = editor.getText().toString();
        for (int i = 0; i < t.length(); i++) {
            if (t.charAt(i) > 255) {
                toast("Türkçe veya özel karakter var. CNC programında bozulur; lütfen kaldırın.");
                return;
            }
        }
        if (editCrLf) t = t.replace("\n", "\r\n");
        final byte[] bytes;
        try {
            bytes = t.getBytes("ISO-8859-1");
        } catch (Exception e) {
            toast("Kodlama hatası.");
            return;
        }
        final String name = editName;
        // dosya ekranina don, yazmayi orada yap (ilerleme cubugu orada)
        showFiles();
        work("Düzenlenen program karta yazılıyor...", new Job() {
            public void run() throws Exception {
                link.put(name, bytes, new BtLink.Progress() {
                    public void onProgress(long d, long t2) { setProgress(d, t2); }
                });
            }
        }, new Runnable() {
            public void run() {
                toast(name + " karta yazıldı.");
                refresh();
            }
        });
    }
}
