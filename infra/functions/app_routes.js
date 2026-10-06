// The app is one page that draws every route itself. A path whose last part has no dot is one of
// its routes and is answered with that page. A path to a file is asked for as it is, so that a
// file that is not there is a 404 and not the app.
function handler(event) {
  const request = event.request;
  if (!request.uri.split('/').pop().includes('.')) {
    request.uri = '/index.html';
  }
  return request;
}
