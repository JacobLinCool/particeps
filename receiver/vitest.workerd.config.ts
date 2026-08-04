import { cloudflareTest } from "@cloudflare/vitest-pool-workers";
import { defineConfig } from "vitest/config";

const CONFIGURATION_SHA256 = "fb2dfea638ca6210e7d15bf12e9bf3c91009d54c8a581dc3a477accd722bb9c7";

export default defineConfig({
  plugins: [
    cloudflareTest({
      wrangler: { configPath: "./wrangler.example.jsonc" },
      miniflare: {
        bindings: {
          UPLOAD_PATH: "/v1/upload",
          ALLOWED_CONFIGURATION_SHA256: CONFIGURATION_SHA256,
          ALLOWED_RESEARCHER_KEY_ID: "vector-hpke",
        },
      },
    }),
  ],
  test: {
    include: ["tests/receiver.workerd.test.ts"],
  },
});
