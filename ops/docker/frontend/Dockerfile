FROM node:20-alpine AS build

WORKDIR /app

RUN npm install -g pnpm

COPY localmind-agent-web/package.json localmind-agent-web/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile

COPY localmind-agent-web/ ./
RUN pnpm build

FROM nginx:1.27-alpine

COPY ops/docker/frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80
