FROM node:24-alpine
ENV NODE_ENV=production HOST=0.0.0.0 PORT=8080 DATA_DIR=/data
WORKDIR /app
COPY --chown=node:node server/ ./
COPY --chown=node:node LICENSE NOTICE ./
RUN mkdir -p /data && chown node:node /data
USER node
EXPOSE 8080
VOLUME ["/data"]
CMD ["node", "src/index.mjs"]
