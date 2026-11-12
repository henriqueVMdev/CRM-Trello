FROM node:24-slim
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev
COPY index.js fila.js ./
RUN mkdir -p /sessao /fila && chown node:node /sessao /fila
USER node
CMD ["node", "index.js"]
