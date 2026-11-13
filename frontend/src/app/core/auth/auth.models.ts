export interface LoginRequest {
  username: string;
  password: string;
}

export interface RegistrationStatus {
  registrationRequired: boolean;
}

export interface RegisterRequest extends LoginRequest {
  locale: string;
}

export interface LoginResponse {
  status: string;
  error?: string;
  locale?: string | null;
  onboardingCompleted?: boolean | null;
}

export interface SessionResponse {
  username: string;
  locale?: string | null;
  onboardingCompleted?: boolean | null;
}
