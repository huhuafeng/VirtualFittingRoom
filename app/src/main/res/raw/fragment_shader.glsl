precision mediump float;

uniform sampler2D uCameraTexture;
uniform sampler2D uClothingTexture;
uniform sampler2D uSegmentationMask;
uniform float uHasClothing;  // 1.0 if clothing is active, 0.0 otherwise

varying vec2 vTexCoord;

void main() {
    vec4 camera = texture2D(uCameraTexture, vTexCoord);

    if (uHasClothing < 0.5) {
        gl_FragColor = camera;
        return;
    }

    vec4 clothing = texture2D(uClothingTexture, vTexCoord);
    float mask = texture2D(uSegmentationMask, vTexCoord).r;

    // Effective alpha: clothing alpha * segmentation mask
    float clothAlpha = clothing.a * mask;

    // Blend clothing over camera
    gl_FragColor = mix(camera, clothing, clothAlpha);
}
