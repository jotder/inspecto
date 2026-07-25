export const environment = {
    appName: 'inspecto',
    production: true,
    hmr: false,
    apiVersion: '/api/v1',
    appLogo: "assets/images/logo/inspecto-logo.svg",
    basePath: '/pronto/',

    authenticationType: 'token',
    footerText: 'Powered by Latro',
    appLogoutUri: 'pronto/logout',
    appClientId: '',

    appLogoutLogo: 'assets/images/logo/inspecto-logo.svg',

    caseTrackerGuiUrl: "/casetracker/",
    authServerAuthentication: true,
    // iam details. No client secret here — this file ships inside the browser bundle (public).
    iamClientId:"",
    dataFormat:"YYYYMMDD",
     notificationSount:'assets/sound/notification_2.mp3'



};




