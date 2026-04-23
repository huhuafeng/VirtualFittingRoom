attribute vec4 aPosition;
attribute vec2 aTexCoord;
varying vec2 vTexCoord;
uniform mat4 uTransform;

void main() {
    vTexCoord = aTexCoord;
    gl_Position = uTransform * aPosition;
}
