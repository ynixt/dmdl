import { HttpInterceptorFn } from '@angular/common/http';

export const credentialsInterceptor: HttpInterceptorFn = (request, next) => {
  if (!isApiRequest(request.url)) {
    return next(request);
  }

  return next(request.clone({ withCredentials: true }));
};

function isApiRequest(url: string): boolean {
  if (url.startsWith('/api')) {
    return true;
  }

  try {
    return new URL(url).pathname.startsWith('/api');
  } catch {
    return false;
  }
}
