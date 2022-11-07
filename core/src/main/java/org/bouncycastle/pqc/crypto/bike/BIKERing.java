package org.bouncycastle.pqc.crypto.bike;

import org.bouncycastle.crypto.Xof;
import org.bouncycastle.math.raw.Interleave;
import org.bouncycastle.math.raw.Nat;
import org.bouncycastle.util.Integers;
import org.bouncycastle.util.Pack;

import static org.bouncycastle.pqc.crypto.bike.BIKEUtils.CHECK_BIT;
import static org.bouncycastle.pqc.crypto.bike.BIKEUtils.SET_BIT;

class BIKERing
{
    private final int bits;
    private final int size;
    private final int sizeExt;

    BIKERing(int r)
    {
        if ((r & 0x80000001) != 1)
            throw new IllegalArgumentException();

        bits = r;
        size = (r + 63) >>> 6;
        sizeExt = size * 2;
    }

    void add(long[] x, long[] y, long[] z)
    {
        for (int i = 0; i < size; ++i)
        {
            z[i] = x[i] ^ y[i];
        }
    }

    void addTo(long[] x, long[] z)
    {
        for (int i = 0; i < size; ++i)
        {
            z[i] ^= x[i];
        }
    }

    void copy(long[] x, long[] z)
    {
        for (int i = 0; i < size; ++i)
        {
            z[i] = x[i];
        }
    }

    long[] create()
    {
        return new long[size];
    }

    long[] createExt()
    {
        return new long[sizeExt];
    }

    void decodeBytes(byte[] bs, long[] z)
    {
        int partialBits = bits & 63;
        Pack.littleEndianToLong(bs, 0, z, 0, size - 1);
        byte[] last = new byte[8];
        System.arraycopy(bs, (size - 1) << 3, last, 0, (partialBits + 7) >>> 3);
        z[size - 1] = Pack.littleEndianToLong(last, 0);
//        assert (z[Size - 1] >> partialBits) == 0L;
    }

    byte[] encodeBits(long[] x)
    {
        byte[] bs = new byte[bits];
        for (int i = 0; i < bits; ++i)
        {
            bs[i] = (byte)((x[i >>> 6] >>> (i & 63)) & 1L);
        }
        return bs;
    }

    void encodeBytes(long[] x, byte[] bs)
    {
        int partialBits = bits & 63;
        assert (x[size - 1] >>> partialBits) == 0L;
        Pack.longToLittleEndian(x, 0, size - 1, bs, 0);
        byte[] last = new byte[8];
        Pack.longToLittleEndian(x[size - 1], last, 0);
        System.arraycopy(last, 0, bs, (size - 1) << 3, (partialBits + 7) >>> 3);
    }

    void inv(long[] a, long[] z)
    {
        long[] f = create();
        long[] g = create();
        long[] t = create();

        copy(a, f);
        copy(a, t);

        int rSub2 = bits - 2;
        int bits = 32 - Integers.numberOfLeadingZeros(rSub2);

        for (int i = 1; i < bits; ++i)
        {
            squareN(f, 1 << (i - 1), g);
            multiply(f, g, f);

            if ((rSub2 & (1 << i)) != 0)
            {
                int n = rSub2 & ((1 << i) - 1);
                squareN(f, n, g);
                multiply(t, g, t);
            }
        }

        square(t, z);
    }

    void multiply(long[] x, long[] y, long[] z)
    {
        long[] tt = createExt();
        implMultiplyAcc(x, y, tt);
        reduce(tt, z);
    }

    void reduce(long[] tt, long[] z)
    {
        int partialBits = bits & 63;
        int excessBits = 64 - partialBits;
        long partialMask = -1L >>> excessBits;

//        long c =
        Nat.shiftUpBits64(size, tt, size, excessBits, tt[size - 1], z, 0);
//        assert c == 0L;
        addTo(tt, z);
        z[size - 1] &= partialMask;
    }

    int getSize()
    {
        return size;
    }

    int getSizeExt()
    {
        return sizeExt;
    }

    void square(long[] x, long[] z)
    {
        long[] tt = createExt();
        implSquare(x, tt);
        reduce(tt, z);
    }

    void squareN(long[] x, int n, long[] z)
    {
        /*
         * TODO In these polynomial rings, 'squareN' for some 'n' is equivalent to a fixed permutation of the
         * coefficients. For 'squareN' with 'n' above some cutoff value, this permutation could be precomputed
         * and then applied in place of explicit squaring for that 'n'. This is particularly relevant to the
         * calls generated by 'inv'.
         */

//        assert n > 0;

        long[] tt = createExt();
        implSquare(x, tt);
        reduce(tt, z);

        while (--n > 0)
        {
            implSquare(z, tt);
            reduce(tt, z);
        }
    }

    protected void implMultiplyAcc(long[] x, long[] y, long[] zz)
    {
        long[] u = new long[16];

        // Schoolbook

//        for (int i = 0; i < size; ++i)
//        {
//            long x_i = x[i];
//
//            for (int j = 0; j < size; ++j)
//            {
//                long y_j = y[j];
//
//                implMulwAcc(u, x_i, y_j, zz, i + j);
//            }
//        }

        // Arbitrary-degree Karatsuba

        for (int i = 0; i < size; ++i)
        {
            implMulwAcc(u, x[i], y[i], zz, i << 1);
        }

        long v0 = zz[0], v1 = zz[1];
        for (int i = 1; i < size; ++i)
        {
            v0 ^= zz[i << 1]; zz[i] = v0 ^ v1; v1 ^= zz[(i << 1) + 1];
        }

        long w = v0 ^ v1;
        for (int i = 0; i < size; ++i)
        {
            zz[size + i] = zz[i] ^ w;
        }

        int last = size - 1;
        for (int zPos = 1; zPos < (last * 2); ++zPos)
        {
            int hi = Math.min(last, zPos);
            int lo = zPos - hi;

            while (lo < hi)
            {
                implMulwAcc(u, x[lo] ^ x[hi], y[lo] ^ y[hi], zz, zPos);

                ++lo;
                --hi;
            }
        }
    }

    private static void implMulwAcc(long[] u, long x, long y, long[] z, int zOff)
    {
//      u[0] = 0;
        u[1] = y;
        for (int i = 2; i < 16; i += 2)
        {
            u[i    ] = u[i >>> 1] << 1;
            u[i + 1] = u[i      ] ^  y;
        }

        int j = (int)x;
        long g, h = 0, l = u[j & 15]
                         ^ u[(j >>> 4) & 15] << 4;
        int k = 56;
        do
        {
            j  = (int)(x >>> k);
            g  = u[j & 15]
               ^ u[(j >>> 4) & 15] << 4;
            l ^= (g << k);
            h ^= (g >>> -k);
        }
        while ((k -= 8) > 0);

        for (int p = 0; p < 7; ++p)
        {
            x = (x & 0xFEFEFEFEFEFEFEFEL) >>> 1;
            h ^= x & ((y << p) >> 63);
        }

//        assert h >>> 63 == 0;

        z[zOff    ] ^= l;
        z[zOff + 1] ^= h;
    }

    private void implSquare(long[] x, long[] zz)
    {
        Interleave.expand64To128(x, 0, size, zz, 0);
    }
}
