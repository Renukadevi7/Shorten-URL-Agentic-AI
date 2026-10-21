import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  /* Allow 127.0.0.1 in dev so client hydration works (not only localhost). */
  allowedDevOrigins: ["127.0.0.1", "localhost"],
  cacheComponents: true,
  partialPrefetching: true,
  turbopack: {
    rules: {
      "*.css": {
        loaders: ["@tailwindcss/turbopack"],
        as: "*.css",
      },
    },
  },
};

export default nextConfig;
