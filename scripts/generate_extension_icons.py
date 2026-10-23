import os
from PIL import Image

def generate_icons():
    source_path = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "java-app", "src", "main", "resources", "static", "logo-icon.png"))
    out_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "extension", "public", "icons"))
    os.makedirs(out_dir, exist_ok=True)
    
    img = Image.open(source_path).convert("RGBA")
    
    # Square canvas with transparent background
    for size in [16, 32, 48, 128]:
        target = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        # Keep aspect ratio
        w, h = img.size
        scale = min(size / w, size / h) * 0.92
        nw, nh = max(1, int(w * scale)), max(1, int(h * scale))
        resized = img.resize((nw, nh), Image.Resampling.LANCZOS)
        
        offset_x = (size - nw) // 2
        offset_y = (size - nh) // 2
        target.paste(resized, (offset_x, offset_y), resized)
        
        out_file = os.path.join(out_dir, f"icon{size}.png")
        target.save(out_file, "PNG")
        print(f"Generated {out_file} ({size}x{size})")

    # Also save icon.png
    out_file = os.path.join(out_dir, "icon.png")
    img.save(out_file, "PNG")
    print(f"Generated {out_file}")

if __name__ == "__main__":
    generate_icons()
