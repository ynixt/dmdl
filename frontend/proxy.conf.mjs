export default {
  '/api': {
    target: 'http://localhost:8080',
    secure: false,
    changeOrigin: true,
    configure(proxy) {
      proxy.on('error', (_error, _request, response) => {
        if (response.writeHead && !response.headersSent) {
          response.writeHead(502, {
            'Content-Type': 'application/json',
            'Cache-Control': 'no-store',
          });
          response.end(JSON.stringify({ code: 'BACKEND_UNAVAILABLE' }));
        }
      });
    },
  },
};
