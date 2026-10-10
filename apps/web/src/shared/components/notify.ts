import { notifications } from '@mantine/notifications';
import i18n from '@/app/i18n';
import { asProblemError } from '@/shared/api/problem';

/** Shows a failed call as a notification; 409s offer a reload because the data changed underneath. */
export function notifyProblem(error: unknown, title?: string): void {
  const problem = asProblemError(error);
  const heading =
    title ??
    (problem.status === 403
      ? i18n.t('errors.forbidden')
      : problem.status === 409
        ? i18n.t('errors.conflict')
        : problem.status === 422
          ? i18n.t('errors.validation')
          : i18n.t('errors.requestFailed'));
  const violations = (problem.problem.violations ?? []).map((v) => `${v.field}: ${v.message}`).join('\n');
  notifications.show({
    color: problem.status === 409 ? 'yellow' : 'red',
    title: heading,
    message: [problem.message, violations].filter(Boolean).join('\n'),
    autoClose: problem.status === 409 ? false : 8000,
  });
}

export function notifySuccess(message: string): void {
  notifications.show({ color: 'teal', message, autoClose: 4000 });
}
