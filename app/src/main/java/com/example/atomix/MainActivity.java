package com.example.atomix; // <- an dein AIDE-Projekt anpassen

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;

/**
 * Atomix: Atome rutschen, bis sie an Wand/Atom stoppen. Baue das Molekuel unten.
 * Bedienung: Atom antippen und in eine Richtung wischen,
 *            oder Atom antippen und danach irgendwo in die gewuenschte Richtung tippen.
 *
 * levels.dat (assets/): 444 Datensaetze a 773 Byte
 *   0       Name als Pascal-String (Laenge, Zeichen)
 *   246     Spielfeld 15x15: 0=Boden, 1..50=Atom-Nr., >=128=Wand/Aussen
 *   471     Ziel-x[50], 521 Ziel-y[50]  (je Atom, gleicher Index wie Atom-Nr.-1)
 *   571     Breite, Hoehe (ungenutzt)
 *   573     je Atom 4 Byte: Typ, Bindungen(8 Richtungen E,NE,N,NW,W,SW,S,SE),
 *           Doppel-/Dreifachbindung (Bits 0-3 / 4-7 = E,N,W,S), 0
 */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        setContentView(new Game(this));
    }

    static class Game extends View {
        static final int REC = 773;
        static final int[] DX = {1, 0, -1, 0}, DY = {0, -1, 0, 1};                    // E,N,W,S
        static final int[] BX = {1, 1, 0, -1, -1, -1, 0, 1}, BY = {0, -1, -1, -1, 0, 1, 1, 1}; // 8 Richtungen
        static final int[] COL = {0xFF888888, 0xFFF5F5F5, 0xFFE53935, 0xFF455A64, 0xFF1E88E5, 0xFFFDD835, 0xFF43A047};

        byte[] data;
        int lv, n, sel = -1;
        String name = "";
        boolean solved;
        int[] map = new int[225];
        int[] ax = new int[50], ay = new int[50];                 // aktuelle Atompositionen
        int[] t = new int[50], b = new int[50], d = new int[50];  // Typ, Bindungen, Doppelbindungen
        int[] gx = new int[50], gy = new int[50];                 // Position im Zielmolekuel
        ArrayList<int[]> hist = new ArrayList<int[]>();           // Zuege: {atom, altX, altY}
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float cs, ox, oy, downX, downY;

        Game(Context c) {
            super(c);
            try {
                InputStream in = c.getAssets().open("levels.dat");
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) > 0) o.write(buf, 0, r);
                in.close();
                data = o.toByteArray();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            p.setTextAlign(Paint.Align.CENTER);
            load(c.getSharedPreferences("atomix", 0).getInt("lv", 0));
        }

        void load(int i) {
            int cnt = data.length / REC;
            lv = (i % cnt + cnt) % cnt;
            int o = lv * REC;
            name = new String(data, o + 1, data[o] & 255, Charset.forName("ISO-8859-1"));
            n = 0;
            for (int k = 0; k < 225; k++) {
                int v = data[o + 246 + k] & 255;
                map[k] = v & 128;
                if (v > 0 && v < 128) {
                    ax[v - 1] = k % 15;
                    ay[v - 1] = k / 15;
                    n = Math.max(n, v);
                }
            }
            for (int k = 0; k < n; k++) {
                gx[k] = data[o + 471 + k];
                gy[k] = data[o + 521 + k];
                t[k] = data[o + 573 + 4 * k] & 255;
                b[k] = data[o + 574 + 4 * k] & 255;
                d[k] = data[o + 575 + 4 * k] & 255;
            }
            sel = -1;
            solved = false;
            hist.clear();
            getContext().getSharedPreferences("atomix", 0).edit().putInt("lv", lv).commit();
            invalidate();
        }

        boolean wall(int x, int y) {
            return x < 0 || y < 0 || x > 14 || y > 14 || map[y * 15 + x] != 0;
        }

        int at(int x, int y) {
            for (int k = 0; k < n; k++) if (ax[k] == x && ay[k] == y) return k;
            return -1;
        }

        int[] slide(int k, int dx, int dy) {
            int x = ax[k], y = ay[k];
            while (!wall(x + dx, y + dy) && at(x + dx, y + dy) < 0) {
                x += dx;
                y += dy;
            }
            return new int[]{x, y};
        }

        void move(int k, int dx, int dy) {
            int[] e = slide(k, dx, dy);
            if (e[0] == ax[k] && e[1] == ay[k]) return;
            hist.add(new int[]{k, ax[k], ay[k]});
            ax[k] = e[0];
            ay[k] = e[1];
            solved = check();
        }

        void undoMove() {
            if (hist.isEmpty()) return;
            int[] u = hist.remove(hist.size() - 1);
            ax[u[0]] = u[1];
            ay[u[0]] = u[2];
            sel = u[0];
            solved = false;
        }

        boolean same(int a, int j) {
            return t[a] == t[j] && b[a] == b[j] && d[a] == d[j];
        }

        /** Gibt es eine Verschiebung des Zielmolekuels, bei der jedes Zielatom von einem gleichen Atom belegt ist? */
        boolean check() {
            for (int k = 0; k < n; k++) {
                if (!same(k, 0)) continue;
                int sx = ax[k] - gx[0], sy = ay[k] - gy[0];
                boolean ok = true;
                for (int j = 0; j < n && ok; j++) {
                    int a = at(sx + gx[j], sy + gy[j]);
                    ok = a >= 0 && same(a, j);
                }
                if (ok) return true;
            }
            return false;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX(), y = e.getY();
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                downX = x;
                downY = y;
            }
            if (e.getAction() != MotionEvent.ACTION_UP) return true;
            if (downY < oy) {                                   // Button-Leiste
                int btn = (int) (downX / (getWidth() / 4f));
                if (btn == 0) load(lv - 1);
                else if (btn == 1) load(lv + 1);
                else if (btn == 2) undoMove();
                else load(lv);
            } else if (solved) {
                load(lv + 1);
            } else {
                int k = at((int) Math.floor((downX - ox) / cs), (int) Math.floor((downY - oy) / cs));
                float dx = x - downX, dy = y - downY;
                if (k >= 0) {
                    sel = k;                                    // Atom gewaehlt, ggf. Wisch-Richtung
                } else if (sel >= 0) {                          // Tipp neben das gewaehlte Atom
                    dx = x - ox - (ax[sel] + .5f) * cs;
                    dy = y - oy - (ay[sel] + .5f) * cs;
                }
                if (sel >= 0 && Math.abs(dx) + Math.abs(dy) > cs / 2) {
                    if (Math.abs(dx) > Math.abs(dy)) move(sel, dx > 0 ? 1 : -1, 0);
                    else move(sel, 0, dy > 0 ? 1 : -1);
                }
            }
            invalidate();
            return true;
        }

        boolean near(int x, int y) {                            // Wand grenzt an begehbares Feld?
            for (int i = -1; i < 2; i++)
                for (int j = -1; j < 2; j++)
                    if (!wall(x + i, y + j)) return true;
            return false;
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            cs = Math.min(w / 15f, h / 22f);
            ox = (w - 15 * cs) / 2;
            oy = 1.5f * cs;
            c.drawColor(0xFF1B1D2B);

            // Button-Leiste
            String[] bt = {"<", ">", "Zur\u00fcck", "Neustart"};
            p.setTextSize(cs * .5f);
            for (int i = 0; i < 4; i++) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFF33384F);
                c.drawRect(i * w / 4f + 4, 4, (i + 1) * w / 4f - 4, oy - 4, p);
                p.setColor(Color.WHITE);
                c.drawText(bt[i], (i + .5f) * w / 4f, oy / 2 + cs * .18f, p);
            }

            // Spielfeld
            for (int y = 0; y < 15; y++)
                for (int x = 0; x < 15; x++) {
                    float l = ox + x * cs, tp = oy + y * cs;
                    if (!wall(x, y)) {
                        p.setColor(((x + y) & 1) == 0 ? 0xFF2B3044 : 0xFF272C3E);
                        c.drawRect(l, tp, l + cs, tp + cs, p);
                    } else if (near(x, y)) {
                        p.setColor(0xFF8C95B5);
                        c.drawRect(l, tp, l + cs, tp + cs, p);
                        p.setColor(0xFF646D8F);
                        c.drawRect(l + cs * .12f, tp + cs * .12f, l + cs * .88f, tp + cs * .88f, p);
                    }
                }

            // Zielfelder des gewaehlten Atoms
            if (sel >= 0)
                for (int i = 0; i < 4; i++) {
                    int[] e = slide(sel, DX[i], DY[i]);
                    if (e[0] != ax[sel] || e[1] != ay[sel]) {
                        p.setColor(0x99FFFFFF);
                        c.drawCircle(ox + (e[0] + .5f) * cs, oy + (e[1] + .5f) * cs, cs * .12f, p);
                    }
                }

            for (int k = 0; k < n; k++) atom(c, ox + (ax[k] + .5f) * cs, oy + (ay[k] + .5f) * cs, cs, k);
            if (sel >= 0) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(cs * .08f);
                p.setColor(Color.YELLOW);
                c.drawCircle(ox + (ax[sel] + .5f) * cs, oy + (ay[sel] + .5f) * cs, cs * .47f, p);
                p.setStyle(Paint.Style.FILL);
            }

            // Titel + Zielmolekuel
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.WHITE);
            p.setTextSize(cs * .5f);
            c.drawText((lv + 1) + "/" + (data.length / REC) + "  " + name + "  -  " + hist.size() + " Z\u00fcge",
                    w / 2f, oy + 15.8f * cs, p);
            int gw = 1, gh = 1;
            for (int k = 0; k < n; k++) {
                gw = Math.max(gw, gx[k] + 1);
                gh = Math.max(gh, gy[k] + 1);
            }
            float top = oy + 16.4f * cs;
            float gs = Math.min(cs, Math.min(w / (float) gw, (h - top) / gh));
            for (int k = 0; k < n; k++)
                atom(c, (w - gw * gs) / 2 + (gx[k] + .5f) * gs, top + (gy[k] + .5f) * gs, gs, k);

            if (solved) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xCC000000);
                c.drawRect(ox, oy + 5 * cs, ox + 15 * cs, oy + 10 * cs, p);
                p.setColor(Color.WHITE);
                p.setTextSize(cs * .8f);
                c.drawText("Gel\u00f6st! (" + hist.size() + " Z\u00fcge)", w / 2f, oy + 7.4f * cs, p);
                p.setTextSize(cs * .5f);
                c.drawText("Tippen = n\u00e4chstes Level", w / 2f, oy + 8.8f * cs, p);
            }
        }

        /** Zeichnet Atom k mit Bindungen mittig bei (x,y), Zellgroesse s. */
        void atom(Canvas c, float x, float y, float s, int k) {
            int ty = t[k];
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(s * .1f);
            p.setColor(0xFFDDDDDD);
            for (int i = 0; i < 8; i++)
                if ((b[k] >> i & 1) != 0) line(c, x, y, s, BX[i], BY[i], 0);
            for (int i = 0; i < 4; i++) {
                if ((d[k] >> i & 1) != 0) {                      // Doppelbindung
                    line(c, x, y, s, DX[i], DY[i], -.1f);
                    line(c, x, y, s, DX[i], DY[i], .1f);
                }
                if ((d[k] >> (i + 4) & 1) != 0) {                // Dreifachbindung
                    line(c, x, y, s, DX[i], DY[i], -.17f);
                    line(c, x, y, s, DX[i], DY[i], 0);
                    line(c, x, y, s, DX[i], DY[i], .17f);
                }
            }
            p.setStyle(Paint.Style.FILL);
            if (ty > 6 && ty < 11) {                             // Ring-Fuellstuecke (Typ 7-10)
                p.setColor(0xFF59607A);
                c.drawCircle(x, y, s * .36f, p);
                p.setStyle(Paint.Style.STROKE);
                p.setColor(0xFFC5CAE9);
                c.drawCircle(x, y, s * .36f, p);
                return;
            }
            p.setColor(ty < 7 ? COL[ty] : ty == 21 ? 0xFF7CB342 : ty == 22 ? 0xFF8D6E63 : ty == 23 ? 0xFFFB8C00
                    : Color.HSVToColor(new float[]{ty * 47 % 360, .55f, .95f}));
            c.drawCircle(x, y, s * .42f, p);
            String lab = ty < 7 ? "?HOCNSF".substring(ty, ty + 1) : ty == 21 ? "Cl" : ty == 22 ? "Br" : ty == 23 ? "P" : "" + ty;
            p.setColor(ty > 1 && ty < 7 && ty != 5 ? Color.WHITE : Color.BLACK);
            p.setTextSize(s * .5f);
            c.drawText(lab, x, y + s * .17f, p);
        }

        void line(Canvas c, float x, float y, float s, int dx, int dy, float off) {
            float px = -dy * off * s, py = dx * off * s;
            c.drawLine(x + px, y + py, x + dx * s / 2 + px, y + dy * s / 2 + py, p);
        }
    }
}
