import { readFileSync } from "node:fs";
import { URL } from "node:url";
import { cloudflareTest } from "@cloudflare/vitest-pool-workers";
import { defineConfig } from "vitest/config";

const CONFIGURATION_SHA256 = JSON.parse(
  readFileSync(new URL("../protocol/v1/conformance-vectors.json", import.meta.url), "utf8"),
).valid.upload_receipt.value.configuration_sha256 as string;

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
