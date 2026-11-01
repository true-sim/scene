package com.truesim.scene.animation;

import com.truesim.common.math.Maths;

/**
 * Transforms of 10 values (translation, rotation, scale) in arrays, as the animator keeps them: composing them down a
 * skeleton, and the quaternion and vector arithmetic of the solvers. Allocates nothing.
 */
final class Kinematics {

    private Kinematics() {
    }

    /** Model transforms (in the skeleton's root's parent's space) from local ones, parents first. */
    static void forward(Skeleton skeleton, double[] local, double[] model) {
        for (int j = 0; j < skeleton.size(); j++) {
            int b = Skeleton.TRANSFORM * j;
            int parent = skeleton.parent(j);
            if (parent < 0) {
                System.arraycopy(local, b, model, b, Skeleton.TRANSFORM);
            } else {
                compose(model, Skeleton.TRANSFORM * parent, local, b, model, b);
            }
        }
    }

    /** {@code out[o..]} = {@code parent[p..]} then {@code child[c..]}: the child's transform in the parent's space. */
    static void compose(double[] parent, int p, double[] child, int c, double[] out, int o) {
        double px = parent[p];
        double py = parent[p + 1];
        double pz = parent[p + 2];
        double qx = parent[p + 3];
        double qy = parent[p + 4];
        double qz = parent[p + 5];
        double qw = parent[p + 6];
        double sx = parent[p + 7];
        double sy = parent[p + 8];
        double sz = parent[p + 9];
        double tx = child[c] * sx;
        double ty = child[c + 1] * sy;
        double tz = child[c + 2] * sz;
        double cx = child[c + 3];
        double cy = child[c + 4];
        double cz = child[c + 5];
        double cw = child[c + 6];
        double csx = child[c + 7];
        double csy = child[c + 8];
        double csz = child[c + 9];
        // the translation rotated by the parent: t + 2w(v x t) + 2 v x (v x t)
        double ux = 2 * (qy * tz - qz * ty);
        double uy = 2 * (qz * tx - qx * tz);
        double uz = 2 * (qx * ty - qy * tx);
        out[o] = px + tx + qw * ux + (qy * uz - qz * uy);
        out[o + 1] = py + ty + qw * uy + (qz * ux - qx * uz);
        out[o + 2] = pz + tz + qw * uz + (qx * uy - qy * ux);
        out[o + 3] = qw * cx + qx * cw + qy * cz - qz * cy;
        out[o + 4] = qw * cy - qx * cz + qy * cw + qz * cx;
        out[o + 5] = qw * cz + qx * cy - qy * cx + qz * cw;
        out[o + 6] = qw * cw - qx * cx - qy * cy - qz * cz;
        out[o + 7] = sx * csx;
        out[o + 8] = sy * csy;
        out[o + 9] = sz * csz;
    }

    /** Vector {@code v[0..3)} rotated by quaternion {@code q[at..at+4)}, in place. */
    static void rotate(double[] q, int at, double[] v) {
        double qx = q[at];
        double qy = q[at + 1];
        double qz = q[at + 2];
        double qw = q[at + 3];
        double ux = 2 * (qy * v[2] - qz * v[1]);
        double uy = 2 * (qz * v[0] - qx * v[2]);
        double uz = 2 * (qx * v[1] - qy * v[0]);
        double x = v[0] + qw * ux + (qy * uz - qz * uy);
        double y = v[1] + qw * uy + (qz * ux - qx * uz);
        double z = v[2] + qw * uz + (qx * uy - qy * ux);
        v[0] = x;
        v[1] = y;
        v[2] = z;
    }

    /** Vector {@code v} rotated by the conjugate of {@code q[at..]}: from its frame into the one it is in. */
    static void unrotate(double[] q, int at, double[] v) {
        q[at] = -q[at];
        q[at + 1] = -q[at + 1];
        q[at + 2] = -q[at + 2];
        rotate(q, at, v);
        q[at] = -q[at];
        q[at + 1] = -q[at + 1];
        q[at + 2] = -q[at + 2];
    }

    /** {@code a[i..]} = {@code a[i..]} times (x, y, z, w). */
    static void multiply(double[] a, int i, double x, double y, double z, double w) {
        double ax = a[i];
        double ay = a[i + 1];
        double az = a[i + 2];
        double aw = a[i + 3];
        a[i] = aw * x + ax * w + ay * z - az * y;
        a[i + 1] = aw * y - ax * z + ay * w + az * x;
        a[i + 2] = aw * z + ax * y - ay * x + az * w;
        a[i + 3] = aw * w - ax * x - ay * y - az * z;
    }

    /** {@code a[i..]} = (x, y, z, w) times {@code a[i..]}. */
    static void premultiply(double[] a, int i, double x, double y, double z, double w) {
        double bx = a[i];
        double by = a[i + 1];
        double bz = a[i + 2];
        double bw = a[i + 3];
        a[i] = w * bx + x * bw + y * bz - z * by;
        a[i + 1] = w * by - x * bz + y * bw + z * bx;
        a[i + 2] = w * bz + x * by - y * bx + z * bw;
        a[i + 3] = w * bw - x * bx - y * by - z * bz;
    }

    /** The rotation of {@code angle} radians about unit axis {@code axis[0..3)} into {@code out[0..4)}. */
    static void axisAngle(double[] axis, double angle, double[] out) {
        double s = Maths.sin(angle / 2);
        out[0] = axis[0] * s;
        out[1] = axis[1] * s;
        out[2] = axis[2] * s;
        out[3] = Maths.cos(angle / 2);
    }

    /** Normalizes the quaternion at {@code at}; one of no length becomes no rotation. */
    static void normalizeQuaternion(double[] q, int at) {
        double length = Maths.sqrt(q[at] * q[at] + q[at + 1] * q[at + 1] + q[at + 2] * q[at + 2]
            + q[at + 3] * q[at + 3]);
        if (length > 0) {
            for (int i = 0; i < 4; i++) {
                q[at + i] /= length;
            }
        } else {
            q[at + 3] = 1;
        }
    }

    /** {@code to[t..]} moved toward {@code from[f..]}'s rotation: the rotation {@code k} of the way from it, short way. */
    static void nlerp(double[] from, int f, double[] to, int t, double k) {
        double dot = from[f] * to[t] + from[f + 1] * to[t + 1] + from[f + 2] * to[t + 2] + from[f + 3] * to[t + 3];
        double sign = dot < 0 ? -1 : 1;
        for (int i = 0; i < 4; i++) {
            to[t + i] = from[f + i] + k * (sign * to[t + i] - from[f + i]);
        }
        normalizeQuaternion(to, t);
    }

    /** Normalizes {@code v[0..3)}; returns its length before. */
    static double normalize(double[] v) {
        double length = Maths.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (length > 0) {
            v[0] /= length;
            v[1] /= length;
            v[2] /= length;
        }
        return length;
    }

    static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    /** {@code out} = {@code a} x {@code b}. */
    static void cross(double[] a, double[] b, double[] out) {
        double x = a[1] * b[2] - a[2] * b[1];
        double y = a[2] * b[0] - a[0] * b[2];
        double z = a[0] * b[1] - a[1] * b[0];
        out[0] = x;
        out[1] = y;
        out[2] = z;
    }

    /** A unit vector across unit vector {@code v}, into {@code out}: v crossed with the axis it is least along. */
    static void across(double[] v, double[] out) {
        boolean alongX = Math.abs(v[0]) >= 0.9;
        out[0] = alongX ? 0 : 1;
        out[1] = alongX ? 1 : 0;
        out[2] = 0;
        cross(v, out, out);
        normalize(out);
    }

    /** The angle between unit vectors, radians. */
    static double angle(double[] a, double[] b) {
        return Maths.acos(Math.max(-1, Math.min(1, dot(a, b))));
    }
}
