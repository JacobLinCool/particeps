declare namespace Cloudflare {
  interface Env {
    BUNDLES: R2Bucket;
    UPLOAD_PATH: string;
    ALLOWED_CONFIGURATION_SHA256: string;
    ALLOWED_RESEARCHER_KEY_ID: string;
  }

  interface GlobalProps {
    mainModule: typeof import("./src/index");
  }
}
