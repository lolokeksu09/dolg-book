FROM node:24-bookworm-slim
WORKDIR /app
COPY package.json ./
COPY server ./server
COPY dist/index.html dist/app.js dist/ledger.js dist/style.css ./dist/
RUN install -d -o node -g node /data
USER node
CMD ["node", "server/start.js"]
