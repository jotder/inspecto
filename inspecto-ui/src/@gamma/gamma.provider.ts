import {
  provideHttpClient,
  withInterceptors,
  withXhr,
} from "@angular/common/http";
import {
  EnvironmentProviders,
  Provider,
  importProvidersFrom,
  inject,
  provideEnvironmentInitializer,
} from "@angular/core";
import { MatDialogModule } from "@angular/material/dialog";
import { MAT_FORM_FIELD_DEFAULT_OPTIONS } from "@angular/material/form-field";
import { GammaConfig } from "@gamma/services/config";
import { GAMMA_CONFIG } from "@gamma/services/config/config.constants";
import { GammaConfirmationService } from "@gamma/services/confirmation";
import {
  GammaLoadingService,
  gammaLoadingInterceptor,
} from "@gamma/services/loading";
import { GammaMediaWatcherService } from "@gamma/services/media-watcher";
import { GammaPlatformService } from "@gamma/services/platform";
import { GammaSplashScreenService } from "@gamma/services/splash-screen";
import { GammaUtilsService } from "@gamma/services/utils";

export type GammaProviderConfig = {
  gamma?: GammaConfig;
};

/**
 * Gamma provider
 */
export const provideGamma = (
  config: GammaProviderConfig,
): Array<Provider | EnvironmentProviders> => {
  // Base providers
  const providers: Array<Provider | EnvironmentProviders> = [
    {
      // Use the 'fill' appearance on Angular Material form fields by default
      provide: MAT_FORM_FIELD_DEFAULT_OPTIONS,
      useValue: {
        appearance: "fill",
      },
    },
    {
      provide: GAMMA_CONFIG,
      useValue: config?.gamma ?? {},
    },

    importProvidersFrom(MatDialogModule),
    provideEnvironmentInitializer(() => inject(GammaConfirmationService)),

    provideHttpClient(withXhr(), withInterceptors([gammaLoadingInterceptor])),
    provideEnvironmentInitializer(() => inject(GammaLoadingService)),

    provideEnvironmentInitializer(() => inject(GammaMediaWatcherService)),
    provideEnvironmentInitializer(() => inject(GammaPlatformService)),
    provideEnvironmentInitializer(() => inject(GammaSplashScreenService)),
    provideEnvironmentInitializer(() => inject(GammaUtilsService)),
  ];

  // The vendored @gamma/lib/mock-api shell was removed (BACKLOG "M4 Fuse remainder", 2026-08-26):
  // THE mock backend is the app-owned app/inspecto/mock, wired in app.config.ts. This provider never
  // received a mockApi config, so the branch that used it was dead.

  // Return the providers
  return providers;
};
