class ApplicationError extends Error {
  constructor(message, { code, statusCode, details } = {}) {
    super(message);
    this.code = code;
    this.status = statusCode;
    this.details = details;
  }
}
class ValidationError extends ApplicationError {
  constructor(message) { super(message, { code: 'invalid_engine_input', statusCode: 400 }); }
}
module.exports = { ApplicationError, ValidationError };
