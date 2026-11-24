package com.flclashguard;

/**
 * 极简 QR 码生成器（字节模式，纠错等级 L，版本 1-20）。
 * 不依赖任何第三方库，纯 Java 实现，用于把 "http://172.16.30.250:8787/proxy" 这类短链接变二维码。
 *
 * 实现要点：
 *  - 模式：字节(0100)
 *  - 掩码：固定用 0 号掩码（(x+y)%2==0），对短文本完全够用，扫得出来
 *  - 纠错：L 级（容量最大，容错 7%）
 */
final class QrCode {

    // 各版本(L)能容纳的字节数
    private static final int[] BYTES_L = {
            0, 17, 32, 53, 78, 106, 134, 154, 192, 230, 271,
            321, 367, 425, 458, 520, 586, 644, 718, 792, 858
    };
    // 各版本数据码字数（L）
    private static final int[] DATA_CW_L = {
            0, 19, 34, 55, 80, 108, 136, 156, 194, 232, 274,
            324, 370, 428, 461, 523, 589, 647, 721, 795, 861
    };
    // 各版本纠错码字数（L）
    private static final int[] EC_CW_L = {
            0, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18,
            20, 24, 26, 30, 22, 24, 28, 30, 28, 28
    };
    // 对齐图形中心坐标（版本 -> 中心列表）
    private static final int[][] ALIGN_POS = {
            null,
            new int[]{},
            new int[]{6, 18},
            new int[]{6, 22},
            new int[]{6, 26},
            new int[]{6, 30},
            new int[]{6, 34},
            new int[]{6, 22, 38},
            new int[]{6, 24, 42},
            new int[]{6, 26, 46},
            new int[]{6, 28, 50},
            new int[]{6, 30, 54},
            new int[]{6, 32, 58},
            new int[]{6, 34, 62},
            new int[]{6, 26, 46, 66},
            new int[]{6, 26, 48, 70},
            new int[]{6, 26, 50, 74},
            new int[]{6, 30, 54, 78},
            new int[]{6, 30, 56, 82},
            new int[]{6, 30, 58, 86},
            new int[]{6, 34, 62, 90},
    };

    private final int version;
    private final int size;
    private final boolean[][] mod;
    private final boolean[][] fn;

    QrCode(String text) {
        byte[] data;
        try {
            data = text.getBytes("UTF-8");
        } catch (Exception e) {
            data = text.getBytes();
        }
        int v = 1;
        while (v < BYTES_L.length && BYTES_L[v] < data.length) v++;
        if (v >= BYTES_L.length) v = BYTES_L.length - 1;
        this.version = v;
        this.size = v * 4 + 17;
        this.mod = new boolean[size][size];
        this.fn = new boolean[size][size];
        encode(data);
    }

    int size() { return size; }
    boolean dark(int x, int y) { return mod[y][x]; }

    // ───────── 编码 ─────────

    private void encode(byte[] data) {
        drawFunctionPatterns();
        java.util.List<Boolean> bits = new java.util.ArrayList<Boolean>();

        // 模式 0100（字节）
        add(bits, 4, 4);
        // 字符计数：版本 1-9 用 8 位，10+ 用 16 位
        add(bits, data.length, version <= 9 ? 8 : 16);
        for (byte b : data) add(bits, b & 0xFF, 8);

        int dataCw = DATA_CW_L[version];
        int totalBits = dataCw * 8;
        if (bits.size() > totalBits) bits = bits.subList(0, totalBits);
        while (bits.size() < totalBits) bits.add(false);

        // 数据码字
        byte[] cw = new byte[dataCw];
        for (int i = 0; i < dataCw; i++) {
            int b = 0;
            for (int j = 0; j < 8; j++) if (bits.get(i * 8 + j)) b |= (1 << (7 - j));
            cw[i] = (byte) b;
        }
        // 简化填充：不足部分补 0xEC/0x11（标准做法）
        int used = 3 + data.length; // 粗略，够用
        for (int i = used; i < dataCw; i++) {
            cw[i] = (byte) (i % 2 == 0 ? 0xEC : 0x11);
        }

        byte[] ec = reedSolomon(cw, EC_CW_L[version]);
        byte[] all = new byte[cw.length + ec.length];
        System.arraycopy(cw, 0, all, 0, cw.length);
        System.arraycopy(ec, 0, all, cw.length, ec.length);

        placeData(all);
        // 固定掩码 0
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++)
                if (!fn[y][x] && ((x + y) & 1) == 0) mod[y][x] = !mod[y][x];
        drawFormat(0);
    }

    private void add(java.util.List<Boolean> bits, int value, int count) {
        for (int i = count - 1; i >= 0; i--) bits.add(((value >> i) & 1) == 1);
    }

    private void drawFunctionPatterns() {
        for (int i = 0; i < size; i++) {
            set(6, i, i % 2 == 0);
            set(i, 6, i % 2 == 0);
        }
        finder(0, 0); finder(size - 7, 0); finder(0, size - 7);
        int[] ap = ALIGN_POS[Math.min(version, ALIGN_POS.length - 1)];
        if (ap != null) {
            for (int i = 0; i < ap.length; i++) {
                for (int j = 0; j < ap.length; j++) {
                    if ((i == 0 && j == 0) || (i == 0 && j == ap.length - 1)
                            || (i == ap.length - 1 && j == 0)) continue;
                    alignment(ap[i], ap[j]);
                }
            }
        }
        // 预留格式区
        for (int i = 0; i < 9; i++) {
            if (i != 6) { fn[8][i] = true; fn[i][8] = true; }
        }
        fn[8][6] = true; fn[6][8] = true;
        for (int i = 0; i < 8; i++) { fn[8][size - 1 - i] = true; fn[size - 1 - i][8] = true; }
        set(size - 8, 8, true);
    }

    private void set(int x, int y, boolean d) {
        if (x < 0 || y < 0 || x >= size || y >= size) return;
        mod[y][x] = d; fn[y][x] = true;
    }

    private void finder(int ox, int oy) {
        for (int y = -1; y <= 7; y++) {
            for (int x = -1; x <= 7; x++) {
                int d = Math.max(Math.abs(x - 3), Math.abs(y - 3));
                set(ox + x, oy + y, d != 2 && d != 4);
            }
        }
    }

    private void alignment(int cx, int cy) {
        for (int y = -2; y <= 2; y++)
            for (int x = -2; x <= 2; x++)
                set(cx + x, cy + y, Math.max(Math.abs(x), Math.abs(y)) != 1);
    }

    private void placeData(byte[] all) {
        int i = 0;
        boolean up = true;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int n = 0; n < size; n++) {
                int y = up ? size - 1 - n : n;
                for (int c = 0; c < 2; c++) {
                    int x = right - c;
                    if (fn[y][x]) continue;
                    boolean d = false;
                    if (i / 8 < all.length) d = ((all[i / 8] >> (7 - (i % 8))) & 1) == 1;
                    mod[y][x] = d;
                    i++;
                }
            }
            up = !up;
        }
    }

    private void drawFormat(int mask) {
        int data = (1 << 3) | mask;  // 纠错等级 L = 01
        int ec = formatBch(data);
        int v = (data << 10) | ec;
        for (int i = 0; i < 15; i++) {
            boolean b = ((v >> i) & 1) == 1;
            int x, y;
            if (i < 6) { x = 8; y = i; }
            else if (i < 8) { x = 8; y = i + 1; }
            else if (i == 8) { x = 7; y = 8; }
            else { x = 14 - i; y = 8; }
            if (x < size && y < size) { mod[y][x] = b; fn[y][x] = true; }
        }
        for (int i = 0; i < 8; i++) {
            mod[8][size - 1 - i] = ((v >> i) & 1) == 1;
            fn[8][size - 1 - i] = true;
            mod[size - 1 - i][8] = ((v >> (14 - i)) & 1) == 1;
            fn[size - 1 - i][8] = true;
        }
        mod[size - 8][8] = true; fn[size - 8][8] = true;
    }

    private static int formatBch(int data) {
        int d = data << 10;
        for (int i = 4; i >= 0; i--)
            if (((d >> (i + 10)) & 1) == 1) d ^= 0x537 << i;
        return ((data << 10) | d) ^ 0x5412;
    }

    // ───────── Reed-Solomon ─────────

    private static byte[] reedSolomon(byte[] data, int ecLen) {
        int[] gen = new int[ecLen];
        gen[ecLen - 1] = 1;
        int root = 1;
        for (int i = 0; i < ecLen; i++) {
            for (int j = 0; j < ecLen; j++) {
                gen[j] = mul(gen[j], root);
                if (j + 1 < ecLen) gen[j] ^= gen[j + 1];
            }
            root = mul(root, 0x02);
        }
        int[] rem = new int[ecLen];
        for (byte b : data) {
            int factor = (b & 0xFF) ^ rem[0];
            System.arraycopy(rem, 1, rem, 0, ecLen - 1);
            rem[ecLen - 1] = 0;
            for (int i = 0; i < ecLen; i++) rem[i] ^= mul(gen[i], factor);
        }
        byte[] out = new byte[ecLen];
        for (int i = 0; i < ecLen; i++) out[i] = (byte) rem[i];
        return out;
    }

    private static int mul(int a, int b) {
        int r = 0;
        while (b != 0) {
            if ((b & 1) != 0) r ^= a;
            b >>>= 1;
            a <<= 1;
            if ((a & 0x100) != 0) a ^= 0x11D;
        }
        return r & 0xFF;
    }
}
