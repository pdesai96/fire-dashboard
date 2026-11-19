# jarvis-core: zero runtime dependencies, so the image is just Node plus the source.
# Builds natively on a Raspberry Pi 3B running 64-bit Raspberry Pi OS (arm64).
FROM node:24-slim

ENV NODE_ENV=production \
    DATA_DIR=/data \
    PORT=8787

WORKDIR /app
COPY package.json ./
COPY src ./src
COPY public ./public

RUN mkdir -p /data && chown node:node /data
USER node
VOLUME ["/data"]
EXPOSE 8787

HEALTHCHECK --interval=60s --timeout=5s --start-period=20s --retries=3 \
  CMD node -e "fetch('http://127.0.0.1:'+(process.env.PORT||8787)+'/api/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"

CMD ["node", "--disable-warning=ExperimentalWarning", "src/main.ts"]
