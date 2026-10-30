# Stage 6: the console UI. Two stages, like the Java image:
#   1. build: Node compiles TypeScript and bundles the app into static files (dist/)
#   2. run:   nginx serves those files and proxies /api to helpdesk-api
# The final image has no Node and no source code, only HTML/CSS/JS and nginx.

FROM node:22-alpine AS build
WORKDIR /app
# dependencies first, so this layer is cached until package.json changes
COPY package.json package-lock.json* ./
RUN if [ -f package-lock.json ]; then npm ci; else npm install; fi
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
