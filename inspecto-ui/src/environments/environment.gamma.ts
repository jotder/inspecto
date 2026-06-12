// This file can be replaced during build by using the `fileReplacements` array.
// `ng build --prod` replaces `environment.ts` with `environment.prod.ts`.
// The list of file replacements can be found in `angular.json`.

export const environment = {
    production: false,
    hmr: false,
    apiVersion: '/api/v1',
    basePath: '/',
    appName: 'inspecto',
    appLogo: "assets/images/logo/inspecto-logo.svg",
    kibanaBase: 'http://p21.pr.pronto/monitoring',
    documentationFrameworkBase: 'http://p20.prod.pronto:3000',
    appUrl: 'http://203.163.246.125:6602', 
    gatewayUrl: 'http://203.163.246.125/',

    gatewayServerUrl: 'http://68.183.16.242:6601',
    authServerUrl: 'http://68.183.16.242:6600/auth',
    ruleServerUrl: 'http://203.163.246.125:6606', 
    caseServerUrl: 'http://68.183.16.242:6605',
    chatUrl: "http://68.183.16.242:5000",
    
    authenticationType: 'token',
    footerText: ' © Powered By Gamma Analytics LLC 2024',
    appLogoutUri: 'logout',
    appClientId: '5829657973124606976',
    appClientSecret: 'REDACTED-SEC-INCIDENT-1',
    appLogoutLogo: 'assets/images/logo/inspecto-logo.svg',
    iamAppUrl: 'https://app1.pronto.lebara.sa/iam-server',
    promptoUrl:'http://68.183.16.242/apps/newchat',
    caseTrackerGuiUrl: "/casetracker/",
    authServerAuthentication: true,
    iamClientId:"1070682796450139008",
    iamClientSecret:"REDACTED-SEC-INCIDENT-1",
    dataFormat:"YYYYMMDD",
    notificationSount:'assets/sound/notification_2.mp3'

};

// REDACTED-SEC-INCIDENT-1


/*
 * For easier debugging in development mode, you can import the following file
 * to ignore zone related error stack frames such as `zone.run`, `zoneDelegate.invokeTask`.
 *
 * This import should be commented out in production mode because it will have a negative impact
 * on performance if an error is thrown.
 */
// import 'zone.js/plugins/zone-error';  // Included with Angular CLI.
