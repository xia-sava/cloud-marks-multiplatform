import { defineConfig } from "vitest/config"
import { variantDefines } from "./src/variants"

// テストは、配布版のホストの名前・呼び出しの URL のホスト・拡張の名前を埋め込んで走らせる。
export default defineConfig({
  define: variantDefines("distributed"),
})
