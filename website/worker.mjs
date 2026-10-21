import {createHandler} from './update-service.mjs';
import {createPublisher} from './release-publish.mjs';
export default createHandler({publisher: createPublisher()});
