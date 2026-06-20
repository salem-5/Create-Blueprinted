package net.swzo.create_blueprinted.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

final class PoseAppliedVertexConsumer implements VertexConsumer {

    private VertexConsumer delegate;
    private final Matrix4f pose = new Matrix4f();
    private final Matrix3f normal = new Matrix3f();
    private float offX, offY, offZ;
    private final Vector3f scratch = new Vector3f();

    void prepare(VertexConsumer delegate, Matrix4f pose, Matrix3f normal, float offX, float offY, float offZ) {
        this.delegate = delegate;
        this.pose.set(pose);
        this.normal.set(normal);
        this.offX = offX;
        this.offY = offY;
        this.offZ = offZ;
    }

    @Override
    public VertexConsumer vertex(double x, double y, double z) {
        pose.transformPosition((float) x + offX, (float) y + offY, (float) z + offZ, scratch);
        delegate.vertex(scratch.x(), scratch.y(), scratch.z());
        return this;
    }

    @Override
    public VertexConsumer normal(float x, float y, float z) {
        normal.transform(x, y, z, scratch);
        delegate.normal(scratch.x(), scratch.y(), scratch.z());
        return this;
    }

    @Override
    public VertexConsumer color(int red, int green, int blue, int alpha) {
        delegate.color(red, green, blue, alpha);
        return this;
    }

    @Override
    public VertexConsumer uv(float u, float v) {
        delegate.uv(u, v);
        return this;
    }

    @Override
    public VertexConsumer overlayCoords(int u, int v) {
        delegate.overlayCoords(u, v);
        return this;
    }

    @Override
    public VertexConsumer uv2(int u, int v) {
        delegate.uv2(u, v);
        return this;
    }

    @Override
    public void endVertex() {
        delegate.endVertex();
    }

    @Override
    public void defaultColor(int red, int green, int blue, int alpha) {
        delegate.defaultColor(red, green, blue, alpha);
    }

    @Override
    public void unsetDefaultColor() {
        delegate.unsetDefaultColor();
    }
}
